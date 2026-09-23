package io.noties.markwon.chatdemo.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.google.gson.Gson
import io.noties.markwon.chatdemo.bean.AgentStep
import io.noties.markwon.chatdemo.bean.AIChatMessage
import io.noties.markwon.chatdemo.bean.Attachment
import io.noties.markwon.chatdemo.bean.SessionSummary
import io.noties.markwon.chatdemo.repository.ChatRoomRepository
import io.noties.markwon.chatdemo.room.AIChatDatabase
import io.noties.markwon.chatdemo.room.IChatRepository
import io.noties.markwon.chatdemo.service.AIConfig
import io.noties.markwon.chatdemo.service.AgentMessage
import io.noties.markwon.chatdemo.service.AgentToolCall
import io.noties.markwon.chatdemo.service.DeepSeekAIService
import io.noties.markwon.chatdemo.service.IAIService
import io.noties.markwon.chatdemo.service.IAIService.AIStreamEvent
import io.noties.markwon.chatdemo.service.OpenAIMessageBuilder
import io.noties.markwon.chatdemo.tool.ToolRegistry
import io.noties.markwon.chatdemo.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 聊天页面 ViewModel（chat-demo 独立实现）
 *
 * 移植自 doslas AIChatViewModel，裁剪了：
 * - 登录态 / VIP / 图片创作 / 联网搜索 / 追问问题 / TTS 等全部业务；
 * - 服务器同步（无 serverMsgId 链路），消息纯本地 Room 持久化。
 *
 * 核心流程：
 * 1. [sendTextMessage]：文本+附件合成一条用户消息 → 立即入库 → [sendToAI] 请求流式回复；
 * 2. SSE Chunk 到达：累加到 AI 消息并回调 UI 增量 appendMarkdown（打字机效果）；
 * 3. Done：AI 消息入库 + 刷新会话摘要；Error：空内容移除占位、有内容保留入库；
 * 4. [retryMessage]：用户消息重发 / AI 消息重新生成（复用原 item 重置内容重流）。
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "ChatViewModel"

        /** 本地创建会话 id 前缀（无服务端会话体系的轻量标识） */
        private const val LOCAL_SESSION_PREFIX = "local_"

        private const val PREFS = "chat_demo_config"
        private const val KEY_CURRENT_SESSION = "current_session"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_AGENT = "agent_enabled"
        /** 首次启动种子数据标记（默认对话只播种一次，删除后不复活） */
        private const val KEY_SEEDED = "seeded"
        /** assets 默认对话数据文件（首次启动展示用，见 ChatViewModel.SeedChatData） */
        private const val SEED_ASSETS_FILE = "chat_seed_data.json"

        /** 上下文消息窗口大小（条数上限） */
        const val MAX_CONTEXT_MSGS = 20

        /**
         * 上下文字符预算：按「正文 + 附件内容估算」从最新往前累计，超出即截断，
         * 防止长历史/大附件撑爆模型上下文窗口（DeepSeek 64K token 量级）。
         */
        const val MAX_CONTEXT_CHARS = 24_000

        /** 非图片附件的上下文成本估算（单文件文本最多约 8000 字符） */
        const val FILE_ATTACH_COST_CHARS = 8_000

        /** 会话标题最大长度 */
        const val MAX_TITLE_LEN = 20

        /** 最多可同时选择图片数 */
        const val MAX_IMAGES = 9

        /** Agent 模式最大工具调用轮数（防止无限循环） */
        const val MAX_AGENT_TURNS = 5

        private const val DEFAULT_TITLE = "新对话"
    }

    // ==================== UI 回调（由 Activity/Adapter 注入实现） ====================

    interface Callbacks {
        /** 消息列表结构变化（新增/移除/清空） → adapter.notifyDataSetChanged() */
        fun onMessageListChanged()

        /** 单条消息内容变化（流式 chunk 增量）→ 命中已绑定 holder 则 appendMarkdown，否则整条重绘 */
        fun onMessageContentChanged(item: ChatMessageItem, delta: String)

        /** 思考过程增量（reasoning_content）→ 思考面板文字追加 */
        fun onThinkingChanged(item: ChatMessageItem, delta: String)

        /** 单条消息 loading/streaming/状态变化 */
        fun onMessageStateChanged(item: ChatMessageItem)

        /** 会话列表变化（侧滑抽屉） */
        fun onSessionListChanged()

        /** 待发送附件变化（预览条重绘） */
        fun onAttachmentsChanged()

        /** 输入态变化（发送/停止按钮、可发送状态） */
        fun onInputUiChanged()

        /** 滚动到底部 */
        fun scrollToBottom()

        /** 错误提示 */
        fun onError(message: String)
    }

    var callbacks: Callbacks? = null

    // ==================== 数据层 ====================

    private val repository: IChatRepository by lazy {
        val db = AIChatDatabase.getInstance(getApplication())
        ChatRoomRepository(db.messageDao(), db.sessionDao())
    }

    private val gson = Gson()

    private val aiService: IAIService by lazy { DeepSeekAIService() }

    private val prefs: SharedPreferences by lazy {
        getApplication<Application>().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private val vmScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ==================== 状态 ====================

    var currentSessionId: String = ""
        private set

    /** 消息列表（Adapter 直接读取） */
    val messages = mutableListOf<ChatMessageItem>()

    /** 会话列表（侧滑抽屉） */
    val sessionList = mutableListOf<SessionSummary>()

    /** 待发送附件（图片可多张，文件仅一个，二者不可共存） */
    val selectedAttachments = mutableListOf<Attachment>()

    var inputText: String = ""
        set(value) {
            field = value
            callbacks?.onInputUiChanged()
        }

    /** 当前活跃流式任务数 */
    var activeStreamCount = 0
        private set

    val isStreaming: Boolean get() = activeStreamCount > 0

    /**
     * Agent 模式开关（持久化）：
     * 开启后请求携带手机工具 schema，模型可调用「设备/存储/电量信息、蓝牙设备列表、
     * 文件读写与搜索、闹钟/定时器、联系人查询、剪贴板、拨号、清缓存」等工具，
     * 多轮循环执行后返回最终答复；需要权限的工具会弹窗向用户申请授权；关闭则普通聊天。
     */
    var agentEnabled: Boolean = false
        private set

    private val activeJobs = mutableListOf<Job>()

    // ==================== 初始化 ====================

    init {
        val startNs = System.nanoTime()
        loadAIConfig()
        agentEnabled = prefs.getBoolean(KEY_AGENT, false)
        currentSessionId = prefs.getString(KEY_CURRENT_SESSION, "").orEmpty()
        vmScope.launch {
            // 首次启动：库为空时写入 assets 种子对话（默认会话 + 示例消息），并作为当前会话展示
            trySeedDefaultData()
            if (currentSessionId.isEmpty()) {
                currentSessionId = LOCAL_SESSION_PREFIX + UUID.randomUUID().toString()
                prefs.edit().putString(KEY_CURRENT_SESSION, currentSessionId).apply()
            }
            AppLog.i(AppLog.TAG_INIT, "ChatViewModel init: session=$currentSessionId, agent=$agentEnabled, model=${AIConfig.model}, baseUrl=${AIConfig.baseUrl}")
            loadMessages()
            loadSessionList()
            AppLog.d(AppLog.TAG_INIT, "ChatViewModel init done, ${AppLog.elapsedMs(startNs)}")
        }
    }

    /**
     * 首次启动种子数据：assets/[SEED_ASSETS_FILE] 中的默认会话与消息。
     *
     * 幂等约束（双保险，避免重复写入）：
     * - [KEY_SEEDED] 标记只播种一次（升级安装的历史版本置位后不再播种）；
     * - 仅当数据库无任何会话时才插入，用户删除全部会话后不会复活默认数据。
     */
    private suspend fun trySeedDefaultData() {
        if (prefs.getBoolean(KEY_SEEDED, false)) return
        withContext(Dispatchers.IO) {
            try {
                if (repository.loadSessions().isNotEmpty()) {
                    AppLog.d(AppLog.TAG_DB, "seed skipped: db already has sessions")
                } else {
                    val raw = getApplication<Application>().assets
                        .open(SEED_ASSETS_FILE).bufferedReader().use { it.readText() }
                    val seed = gson.fromJson(raw, SeedChatData::class.java)
                    seed.messages.forEach { it.sessionId = seed.session.sessionId }
                    seed.messages.forEach { repository.saveMessage(it) }
                    repository.saveSession(seed.session)
                    // 使默认会话成为当前会话，首次打开即可看到展示内容
                    currentSessionId = seed.session.sessionId
                    prefs.edit().putString(KEY_CURRENT_SESSION, currentSessionId).apply()
                    AppLog.i(AppLog.TAG_DB, "seed default data: session=${seed.session.sessionId}, messages=${seed.messages.size}")
                }
            } catch (e: Exception) {
                AppLog.e(AppLog.TAG_DB, "seed default data failed", e)
            } finally {
                prefs.edit().putBoolean(KEY_SEEDED, true).apply()
            }
        }
    }

    /** assets 种子对话结构（对应 chat_seed_data.json 文件） */
    private data class SeedChatData(
        val session: SessionSummary = SessionSummary(),
        val messages: List<AIChatMessage> = emptyList(),
    )

    /** 切换 Agent 模式 */
    fun toggleAgent() {
        if (isStreaming) return
        agentEnabled = !agentEnabled
        prefs.edit().putBoolean(KEY_AGENT, agentEnabled).apply()
        callbacks?.onInputUiChanged()
    }

    // ==================== 模型配置（切换） ====================

    private fun loadAIConfig() {
        AIConfig.baseUrl = prefs.getString(KEY_BASE_URL, AIConfig.baseUrl) ?: AIConfig.baseUrl
        AIConfig.apiKey = prefs.getString(KEY_API_KEY, AIConfig.apiKey) ?: AIConfig.apiKey
        AIConfig.model = prefs.getString(KEY_MODEL, AIConfig.model) ?: AIConfig.model
    }

    /** 保存配置并立即生效（下一次请求即用新 baseUrl/apiKey/model） */
    fun saveAIConfig(baseUrl: String, apiKey: String, model: String) {
        AIConfig.baseUrl = baseUrl.trim().ifEmpty { AIConfig.baseUrl }
        AIConfig.apiKey = apiKey.trim()
        AIConfig.model = model.trim().ifEmpty { AIConfig.model }
        prefs.edit()
            .putString(KEY_BASE_URL, AIConfig.baseUrl)
            .putString(KEY_API_KEY, AIConfig.apiKey)
            .putString(KEY_MODEL, AIConfig.model)
            .apply()
    }

    // ==================== 会话操作 ====================

    /** 新建对话 */
    fun createNewSession() {
        if (isStreaming) return
        cancelAllStreams()
        currentSessionId = LOCAL_SESSION_PREFIX + UUID.randomUUID().toString()
        prefs.edit().putString(KEY_CURRENT_SESSION, currentSessionId).apply()
        messages.clear()
        selectedAttachments.clear()
        inputText = ""
        callbacks?.onMessageListChanged()
        callbacks?.onAttachmentsChanged()
        loadSessionList()
    }

    /** 切换会话 */
    fun switchSession(sessionId: String) {
        if (isStreaming) return
        if (sessionId == currentSessionId) return
        currentSessionId = sessionId
        prefs.edit().putString(KEY_CURRENT_SESSION, sessionId).apply()
        messages.clear()
        callbacks?.onMessageListChanged()
        loadMessages()
        loadSessionList()
    }

    /** 删除会话（同时删除其全部消息）；删除当前会话则新建空会话 */
    fun deleteSession(sessionId: String) {
        vmScope.launch {
            repository.deleteSession(sessionId)
            if (sessionId == currentSessionId) {
                currentSessionId = LOCAL_SESSION_PREFIX + UUID.randomUUID().toString()
                prefs.edit().putString(KEY_CURRENT_SESSION, currentSessionId).apply()
                messages.clear()
                callbacks?.onMessageListChanged()
                loadMessages()
            }
            loadSessionList()
        }
    }

    fun loadSessionList() {
        vmScope.launch {
            val startNs = System.nanoTime()
            val list = repository.loadSessions()
            list.forEach { it.isSelected = it.sessionId == currentSessionId }
            sessionList.clear()
            sessionList.addAll(list)
            callbacks?.onSessionListChanged()
            AppLog.d(AppLog.TAG_DB, "loadSessions: ${list.size} 条, ${AppLog.elapsedMs(startNs)}")
        }
    }

    fun loadMessages() {
        vmScope.launch {
            val startNs = System.nanoTime()
            // 清理上次未完成（进程被杀/异常中断）残留的 SENDING AI 回复
            repository.deleteAssistantSending(currentSessionId)
            val items = repository.loadMessages(currentSessionId).map { ChatMessageItem(it) }
            messages.clear()
            messages.addAll(items)
            callbacks?.onMessageListChanged()
            if (items.isNotEmpty()) {
                callbacks?.scrollToBottom()
            }
            AppLog.i(AppLog.TAG_DB, "loadMessages[$currentSessionId]: ${items.size} 条消息, ${AppLog.elapsedMs(startNs)}")
        }
    }

    // ==================== 附件 ====================

    val canSend: Boolean
        get() = inputText.isNotBlank() || selectedAttachments.isNotEmpty()

    /** 添加附件：图片多张（最多 [MAX_IMAGES]），文件仅一个且与图片互斥 */
    fun addAttachment(attachment: Attachment) {
        if (attachment.isImage()) {
            selectedAttachments.removeAll { !it.isImage() }
            if (selectedAttachments.size < MAX_IMAGES) {
                selectedAttachments.add(attachment)
            }
        } else {
            selectedAttachments.clear()
            selectedAttachments.add(attachment)
        }
        callbacks?.onAttachmentsChanged()
        callbacks?.onInputUiChanged()
    }

    fun removeAttachment(attachment: Attachment) {
        selectedAttachments.remove(attachment)
        callbacks?.onAttachmentsChanged()
        callbacks?.onInputUiChanged()
    }

    // ==================== 发送 / 流式 ====================

    fun sendTextMessage() {
        if (isStreaming) return
        val text = inputText.trim()
        val attachments = selectedAttachments.toList()
        if (text.isEmpty() && attachments.isEmpty()) return

        AppLog.i(AppLog.TAG_SEND, "send: textLen=${text.length}, attachments=${attachments.size}" +
                "(${attachments.joinToString { it.fileName ?: "?" }}), agent=$agentEnabled, session=$currentSessionId")

        val userMessage = AIChatMessage(
            sessionId = currentSessionId,
            role = AIChatMessage.ROLE_USER,
            type = AIChatMessage.TYPE_TEXT,
            content = text,
            attachments = attachments,
            status = AIChatMessage.STATUS_SENDING
        )
        addMessage(userMessage)
        scrollToBottom()

        // 立即清空输入态
        inputText = ""
        selectedAttachments.clear()
        callbacks?.onAttachmentsChanged()

        // 用户消息入库 + 刷新会话摘要（chat-demo 必须真实持久化）
        vmScope.launch {
            repository.saveMessage(userMessage)
            updateSessionTitle()
            loadSessionList()
        }

        sendToAI(userMessage.id)
    }

    private fun addMessage(message: AIChatMessage) {
        messages.add(ChatMessageItem(message))
        callbacks?.onMessageListChanged()
    }

    private fun scrollToBottom() {
        callbacks?.scrollToBottom()
    }

    /**
     * 发送到 AI 获取流式回复
     * @param userMsgId 触发此回复的用户消息 ID（状态回调仅更新此条消息）
     */
    private fun sendToAI(userMsgId: String) {
        val sessionId = currentSessionId
        val userItem = messages.firstOrNull { it.message.id == userMsgId }

        // 入口立即将用户消息标记为 SUCCESS（进入上下文），失败再回退 FAILED
        userItem?.message?.status = AIChatMessage.STATUS_SUCCESS
        vmScope.launch {
            userItem?.message?.let { repository.saveMessage(it) }
        }
        userItem?.let { callbacks?.onMessageStateChanged(it) }

        // 上下文：只取成功消息；先按条数取最近 MAX_CONTEXT_MSGS 条，再按字符预算从最新往前裁剪
        val contextMessages = trimByCharBudget(
            messages
                .map { it.message }
                .filter { it.status == AIChatMessage.STATUS_SUCCESS }
                .takeLast(MAX_CONTEXT_MSGS)
        )
        AppLog.d(AppLog.TAG_SEND, "sendToAI: userMsgId=$userMsgId, context=${contextMessages.size} 条" +
                "(附件消息 ${contextMessages.count { it.attachments.isNotEmpty() }}, 预算 $MAX_CONTEXT_CHARS chars)")

        // AI 占位（思考中）
        val aiMessage = AIChatMessage(
            sessionId = sessionId,
            role = AIChatMessage.ROLE_AI,
            type = AIChatMessage.TYPE_TEXT,
            content = "",
            status = AIChatMessage.STATUS_SENDING,
            fatherMsgId = userMsgId
        )
        val aiItem = ChatMessageItem(aiMessage).apply {
            loading = true
            streamedLength = 0
            isAgent = agentEnabled
        }
        messages.add(aiItem)
        callbacks?.onMessageListChanged()
        scrollToBottom()

        activeStreamCount++
        callbackInputUi()

        val job = vmScope.launch {
            try {
                runReply(aiItem, aiMessage, userItem, contextMessages)
            } catch (e: CancellationException) {
                // 主动停止：保留已有内容，不视为失败
                errorHandel(aiItem, aiMessage, userItem, sessionId, cancel = true, message = null)
            } catch (e: Exception) {
                Log.e(TAG, "sendToAI error", e)
                errorHandel(aiItem, aiMessage, userItem, sessionId, cancel = false, message = e.message)
            }
        }
        activeJobs.add(job)
    }

    /**
     * 统一流式回复入口：
     * - 普通模式：单轮 sendMessageStream
     * - Agent 模式：多轮 agentStream（携带手机工具 schema），模型请求工具时执行并回传结果，
     *   直至模型输出最终文本或达到 [MAX_AGENT_TURNS]
     */
    private suspend fun runReply(
        aiItem: ChatMessageItem,
        aiMessage: AIChatMessage,
        userItem: ChatMessageItem?,
        initialContext: List<AIChatMessage>
    ) {
        val sessionId = aiMessage.sessionId
        val replyStartNs = System.nanoTime()
        AppLog.i(AppLog.TAG_STREAM, "runReply start: mode=${if (agentEnabled) "AGENT" else "CHAT"}, msgId=${aiMessage.id}")
        if (!agentEnabled) {
            // ===== 普通聊天 =====
            aiService.sendMessageStream(initialContext).collect { event ->
                when (event) {
                    is AIStreamEvent.ThinkingChunk -> onThinking(aiItem, event.content)
                    is AIStreamEvent.Chunk -> onChunk(aiItem, aiMessage, event.content)
                    is AIStreamEvent.Done -> {
                        // 仅在未终止（Error/停止未发生过）时才完成收尾
                        if (aiMessage.status == AIChatMessage.STATUS_SENDING) {
                            finishAI(aiItem, sessionId)
                        }
                    }
                    is AIStreamEvent.Error -> errorHandel(
                        aiItem, aiMessage, userItem, sessionId, cancel = false, message = event.message
                    )
                    is AIStreamEvent.Start -> Unit
                    is AIStreamEvent.ToolCall -> Unit
                }
            }
            // 兜底：流正常关闭但 Done/Error 事件因故未到达（防 activeStreamCount 卡死）
            if (aiMessage.status == AIChatMessage.STATUS_SENDING) {
                AppLog.w(AppLog.TAG_STREAM, "stream closed without Done/Error event, fallback finish")
                if (aiMessage.content.isNotEmpty()) {
                    finishAI(aiItem, sessionId)
                } else {
                    errorHandel(
                        aiItem, aiMessage, userItem, sessionId,
                        cancel = false, message = "连接已关闭但未收到回复"
                    )
                }
            }
            return
        }

        // ===== Agent 模式：多轮工具调用循环 =====
        // 常规 Agent 协议链路：
        // system(系统指令) → history(历史 user/assistant，含附件内容) → [tools 定义挂载在每轮请求上]
        // → 模型轮：reasoning(思考) + content + assistant tool_calls
        // → 执行工具 → tool 结果消息 → 再次请求，直到模型不再请求工具
        // 附件（图片 Base64/文件文本）读取走 IO 线程
        val agentContext = withContext(Dispatchers.IO) {
            buildAgentContext(initialContext)
        }
        val tools = ToolRegistry.buildToolsSchema()
        var turn = 0
        while (turn < MAX_AGENT_TURNS) {
            turn++
            // 多轮思考天然独立成段（新一轮 reasoning 到达时 trailAppendThinking 自动新建思考段），
            // 工具段插在其后，无需再插入人工轮次分隔标记
            AppLog.d(AppLog.TAG_AGENT, "agent turn $turn/$MAX_AGENT_TURNS start, context=${agentContext.size} 条")
            val pendingToolCalls = ArrayList<AgentToolCall>()
            // 本轮起始 content（用于下一轮 assistant 消息携带本轮已输出的文本）
            val turnStartContentLen = aiMessage.content.length
            aiService.agentStream(agentContext, tools).collect { event ->
                when (event) {
                    is AIStreamEvent.ThinkingChunk -> onThinking(aiItem, event.content)
                    is AIStreamEvent.Chunk -> onChunk(aiItem, aiMessage, event.content)
                    is AIStreamEvent.ToolCall -> pendingToolCalls.add(event.toolCall)
                    is AIStreamEvent.Done -> {
                        if (pendingToolCalls.isEmpty() && aiMessage.status == AIChatMessage.STATUS_SENDING) {
                            finishAI(aiItem, sessionId)
                        }
                    }
                    is AIStreamEvent.Error -> errorHandel(
                        aiItem, aiMessage, userItem, sessionId, cancel = false, message = event.message
                    )
                    is AIStreamEvent.Start -> Unit
                }
            }

            if (aiMessage.status != AIChatMessage.STATUS_SENDING) {
                // 已通过 Error/Done 结束
                return
            }
            if (pendingToolCalls.isEmpty()) {
                finishAI(aiItem, sessionId)
                return
            }

            // assistant 工具调用消息：携带本轮已输出的文本（OpenAI 协议允许 content 与 tool_calls 并存）
            agentContext.add(
                AgentMessage(
                    role = "assistant",
                    content = aiMessage.content.substring(turnStartContentLen),
                    toolCalls = pendingToolCalls
                )
            )
            // 逐条执行手机工具并追加 tool 结果消息
            for (toolCall in pendingToolCalls) {
                val step = AgentStep(
                    toolName = toolCall.name,
                    arguments = toolCall.arguments,
                    status = AgentStep.STATUS_RUNNING
                )
                // 工具步骤挂到当前轮的工具段（紧跟本轮思考段之后），保持「思考 → 工具」顺序
                aiItem.trailTools().steps.add(step)
                callbacks?.onMessageStateChanged(aiItem)

                val toolStartNs = System.nanoTime()
                AppLog.i(AppLog.TAG_TOOL, "tool exec: ${toolCall.name}, argsLen=${toolCall.arguments.length}, args=${toolCall.arguments.take(120)}")
                val result = withContext(Dispatchers.IO) {
                    ToolRegistry.execute(getApplication(), toolCall.name, toolCall.arguments)
                }
                step.status = if (result.startsWith("错误") || result.startsWith("工具执行异常")) {
                    AgentStep.STATUS_FAILED
                } else {
                    AgentStep.STATUS_DONE
                }
                step.summary = result.trim().take(160)
                callbacks?.onMessageStateChanged(aiItem)
                AppLog.i(AppLog.TAG_TOOL, "tool done: ${toolCall.name}, status=${step.statusText()}, resultLen=${result.length}, ${AppLog.elapsedMs(toolStartNs)}")

                agentContext.add(
                    AgentMessage(role = "tool", content = result, toolCallId = toolCall.id)
                )
            }
            scrollToBottom()
            // 下一轮：把工具结果带回给模型
        }
        // 达到最大轮数：兜底结束
        finishAI(aiItem, sessionId)
    }

    /**
     * 上下文字符预算裁剪：从最新往前累计（正文 + 附件成本估算），超出预算即停止。
     * 保证最新上下文完整，旧消息被丢弃。
     */
    private fun trimByCharBudget(messages: List<AIChatMessage>): List<AIChatMessage> {
        if (messages.isEmpty()) return messages
        var budget = MAX_CONTEXT_CHARS
        val out = mutableListOf<AIChatMessage>()
        for (msg in messages.asReversed()) {
            val cost = msg.content.length +
                    msg.attachments.count { !it.isImage() } * FILE_ATTACH_COST_CHARS
            if (cost > budget && out.isNotEmpty()) {
                AppLog.i(AppLog.TAG_BUILD, "context budget cut: keep latest ${out.size}/${messages.size} msgs (cost=$cost, left=$budget)")
                break
            }
            // 单条超预算但列表为空时仍保留（至少要有一条触发消息）
            budget -= cost
            out.add(msg)
        }
        return out.asReversed()
    }

    /** 构建 Agent 上下文（用户/助手成功消息 → AgentMessage）。
     * 用户消息走 [OpenAIMessageBuilder.buildMessageContent]：附件（图片 Base64 / 文件文本）
     * 一并进入上下文，与普通聊天模式一致；助手消息保持纯文本。
     */
    private fun buildAgentContext(contextMessages: List<AIChatMessage>): MutableList<AgentMessage> {
        val startNs = System.nanoTime()
        val result = contextMessages.map { msg ->
            AgentMessage(
                role = if (msg.isUser()) "user" else "assistant",
                content = if (msg.isUser()) {
                    OpenAIMessageBuilder.buildMessageContent(msg)
                } else {
                    msg.content
                }
            )
        }.toMutableList()
        AppLog.d(AppLog.TAG_BUILD, "buildAgentContext: ${result.size} 条(含附件 user ${contextMessages.count { it.isUser() && it.attachments.isNotEmpty() }}), ${AppLog.elapsedMs(startNs)}")
        return result
    }

    /** 思考过程增量 */
    private fun onThinking(item: ChatMessageItem, chunk: String) {
        val first = item.thinking.isEmpty()
        // 追加到轨迹：尾部是思考段则续写，否则（上一轮工具段之后）自动新建独立思考段，
        // 天然形成「思考 → 工具 → 思考 → 工具」的按轮交错展示
        item.trailAppendThinking(chunk)
        if (first) {
            AppLog.i(AppLog.TAG_THINK, "thinking start, msgId=${item.message.id}")
        } else {
            AppLog.v(AppLog.TAG_THINK, "thinking +${chunk.length} chars (total ${item.thinking.length})")
        }
        callbacks?.onThinkingChanged(item, chunk)
    }

    /** 重新生成 AI 回复（复用原 item 重置内容后重新流式请求） */
    private fun retryAIReply(item: ChatMessageItem) {
        val aiMessage = item.message
        aiMessage.status = AIChatMessage.STATUS_SENDING
        item.content = ""
        item.streamedLength = 0
        item.loading = true
        item.streaming = false
        item.clearTrail()
        item.isAgent = agentEnabled
        callbacks?.onMessageStateChanged(item)

        // 上下文：取该 AI 回复之前的所有成功消息（含其关联用户消息），按字符预算裁剪
        val index = messages.indexOf(item)
        val contextMessages = trimByCharBudget(
            messages.subList(0, index)
                .map { it.message }
                .filter { it.status == AIChatMessage.STATUS_SUCCESS }
                .takeLast(MAX_CONTEXT_MSGS)
        )

        val sessionId = currentSessionId
        activeStreamCount++
        callbackInputUi()

        val job = vmScope.launch {
            try {
                runReply(item, aiMessage, null, contextMessages)
            } catch (e: CancellationException) {
                errorHandel(item, aiMessage, null, sessionId, cancel = true, message = null)
            } catch (e: Exception) {
                Log.e(TAG, "retryAIReply error", e)
                errorHandel(item, aiMessage, null, sessionId, cancel = false, message = e.message)
            }
        }
        activeJobs.add(job)
    }

    /** 重发失败的用户消息 */
    private fun retryUserMessage(item: ChatMessageItem) {
        val message = item.message
        message.status = AIChatMessage.STATUS_SENDING
        message.timestamp = System.currentTimeMillis()
        callbacks?.onMessageStateChanged(item)
        vmScope.launch {
            repository.saveMessage(message)
        }
        scrollToBottom()
        sendToAI(message.id)
    }

    /** 消息重试入口（气泡操作按钮触发） */
    fun retryMessage(item: ChatMessageItem) {
        if (isStreaming) return
        if (item.message.isUser()) {
            retryUserMessage(item)
        } else {
            retryAIReply(item)
        }
    }

    /** 停止当前所有流式回复 */
    fun stopClick() {
        if (!isStreaming) return
        AppLog.i(AppLog.TAG_STREAM, "stop clicked, cancelling ${activeJobs.size} jobs")
        aiService.cancelRequest()
        cancelAllStreams()
    }

    private fun cancelAllStreams() {
        activeJobs.forEach { it.cancel() }
        activeJobs.clear()
        activeStreamCount = 0
        callbackInputUi()
    }

    /** 流式 Chunk 到达：累加内容并增量推送视图 */
    private fun onChunk(item: ChatMessageItem, aiMessage: AIChatMessage, chunk: String) {
        aiMessage.content += chunk
        item.content = aiMessage.content
        item.streamedLength = aiMessage.content.length
        if (aiMessage.content.isNotEmpty()) {
            // 仅在「思考中 → 有内容」的临界点通知一次（loading 翻转），
            // 避免每个 chunk 触发整条重绘破坏 appendMarkdown 打字机效果
            if (item.loading) {
                item.loading = false
                item.streaming = true
                callbacks?.onMessageStateChanged(item)
                AppLog.i(AppLog.TAG_STREAM, "first chunk arrived, typing starts (msgId=${aiMessage.id})")
            } else {
                item.streaming = true
            }
        }
        AppLog.v(AppLog.TAG_STREAM, "chunk +${chunk.length} chars (total ${aiMessage.content.length})")
        // 增量推送（Delta）：命中已绑定视图则 appendMarkdown，否则整条重绘
        callbacks?.onMessageContentChanged(item, chunk)
    }

    /** 流式结束：AI 消息入库 + 刷新会话摘要 */
    private fun finishAI(item: ChatMessageItem, sessionId: String) {
        val aiMessage = item.message
        aiMessage.status = AIChatMessage.STATUS_SUCCESS
        item.loading = false
        item.streaming = false
        callbacks?.onMessageStateChanged(item)
        // 轨迹序列化随消息入库（重进会话可恢复「思考/工具」交错顺序展示）
        item.syncTrailToJson()
        AppLog.i(AppLog.TAG_STREAM, "reply finished: contentLen=${aiMessage.content.length}, thinkingLen=${item.thinking.length}, steps=${item.agentSteps.size}, msgId=${aiMessage.id}")
        // 兜底重绘，确保最终内容与视图一致（Delta 为空时整条重绘）
        callbacks?.onMessageContentChanged(item, "")
        vmScope.launch {
            repository.saveMessage(aiMessage)
            updateSessionTitle()
            loadSessionList()
            AppLog.d(AppLog.TAG_DB, "AI reply saved: ${aiMessage.id}")
        }
        scrollToBottom()
        decrementActiveStream()
    }

    /**
     * 错误/取消处理
     * - 未收到任何内容：移除 AI 占位；有内容：保留并入库（cancel 时保持 SUCCESS）
     * - 用户消息：失败时回退 FAILED
     */
    private fun errorHandel(
        aiItem: ChatMessageItem?,
        aiMessage: AIChatMessage?,
        userItem: ChatMessageItem?,
        sessionId: String,
        cancel: Boolean,
        message: String?
    ) {
        AppLog.w(AppLog.TAG_STREAM, "error/cancel: cancel=$cancel, contentLen=${aiMessage?.content?.length ?: 0}, msg=${message ?: "主动停止"}")
        if (aiItem != null) {
            if (cancel) {
                // 主动停止：保留部分内容，视为已完成（避免以 SENDING 状态入库）
                aiMessage?.status = AIChatMessage.STATUS_SUCCESS
            } else {
                aiMessage?.status = AIChatMessage.STATUS_FAILED
            }
            aiItem.loading = false
            aiItem.streaming = false
            callbacks?.onMessageStateChanged(aiItem)

            if (aiMessage?.content.isNullOrEmpty()) {
                // 未收到任何内容：移除占位
                messages.remove(aiItem)
                callbacks?.onMessageListChanged()
            } else {
                // 保留部分内容入库（含 thinking 与已执行步骤，轨迹序列化保住交错的展示顺序）
                aiItem.content = aiMessage?.content.orEmpty()
                aiItem.syncTrailToJson()
                aiMessage?.let {
                    vmScope.launch {
                        repository.saveMessage(it)
                        updateSessionTitle()
                        loadSessionList()
                    }
                }
            }
        }

        userItem?.let {
            if (!cancel) {
                it.message.status = AIChatMessage.STATUS_FAILED
                callbacks?.onMessageStateChanged(it)
            }
            vmScope.launch {
                repository.saveMessage(it.message)
            }
        }

        if (!cancel) {
            callbacks?.onError(message ?: "网络请求失败，请重试")
        }
        decrementActiveStream()
    }

    private fun decrementActiveStream() {
        if (activeStreamCount > 0) activeStreamCount--
        if (activeStreamCount < 0) activeStreamCount = 0
        callbackInputUi()
    }

    private fun callbackInputUi() {
        callbacks?.onInputUiChanged()
    }

    /** 刷新会话摘要（标题=首条用户消息，lastMessage=最新内容） */
    private suspend fun updateSessionTitle() {
        val client = sessionTitleClient
        val existing = repository.getSession(currentSessionId)
        val session = existing ?: SessionSummary(sessionId = currentSessionId)

        val firstUserText = client.firstUserText
        val lastText = client.lastText
        if (session.title.isBlank()) {
            session.title = firstUserText ?: DEFAULT_TITLE
        }
        session.lastMessage = lastText
        session.messageCount = messages.size
        session.timestamp = System.currentTimeMillis()
        repository.saveSession(session)
    }

    /** 会话摘要文本提取辅助（内聚，避免 updateSessionTitle 过长） */
    private val sessionTitleClient by lazy {
        object {
            val firstUserText: String?
                get() = messages.firstOrNull { it.message.isUser() }?.message?.content
                    ?.takeIf { it.isNotBlank() }
                    ?.let { truncate(it, MAX_TITLE_LEN) }

            val lastText: String
                get() {
                    val content = messages.lastOrNull { it.message.content.isNotBlank() }?.message?.content
                    return if (content.isNullOrBlank()) {
                        "[附件]"
                    } else {
                        truncate(content.replace('\n', ' '), 30)
                    }
                }
        }
    }

    private fun truncate(text: String, max: Int): String =
        if (text.length > max) text.take(max) + "…" else text

    override fun onCleared() {
        vmScope.cancel()
        super.onCleared()
    }
}