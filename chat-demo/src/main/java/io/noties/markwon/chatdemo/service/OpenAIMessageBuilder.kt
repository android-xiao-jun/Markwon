package io.noties.markwon.chatdemo.service

import io.noties.markwon.chatdemo.bean.AIChatMessage
import io.noties.markwon.chatdemo.bean.Attachment
import io.noties.markwon.chatdemo.util.AppLog
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * OpenAI 兼容请求体构造（消息上下文 → JSON）
 *
 * 单一职责：把业务消息（普通 [AIChatMessage] / Agent [AgentMessage]）
 * 转换成 OpenAI /chat/completions 的请求体 JSON。
 *
 * 包含：
 * - 系统提示词 [SYSTEM_PROMPT]
 * - 普通消息：user/assistant + 多模态内容（图片 Base64 / 文件文本）
 * - Agent 消息：tool 消息、assistant tool_calls 消息
 * - tools schema 附加与 stream=true
 *
 * 不涉及网络与配置（baseUrl/apiKey/model 由服务层传入）。
 */
object OpenAIMessageBuilder {

    const val TAG = "OpenAIMessageBuilder"

    /** 单附件图片最大可发送体积 */
    const val MAX_IMAGE_BYTES = 5 * 1024 * 1024L

    /** 单附件文件文本最多附带字符数 */
    const val MAX_FILE_TEXT_LENGTH = 8000

    /** 可直接读取文本内容传给模型的文件后缀白名单（其余二进制格式仅传说明） */
    val TEXT_FILE_EXTS = setOf(
        "txt", "md", "json", "xml", "kt", "java", "py", "js", "ts", "html", "css",
        "csv", "log", "yml", "yaml", "gradle", "properties", "sql", "c", "cpp", "h", "sh", "bat"
    )

    /**
     * 基础系统提示词（普通聊天 / Agent 模式共用）：
     * - 身份定位：Android 手机端 AI 助手，回复经 Markwon 流式渲染
     * - Markdown 规范与移动端排版约束
     * - 附件占位约定（与 [buildMessageContent] 注入的【附件文件…】【图片附件…】格式一一对应）
     */
    const val SYSTEM_PROMPT = """
你是运行在 Android 手机上的 AI 聊天助手，回复会以流式 Markdown 渲染显示在手机聊天界面。

## 语言
始终使用用户消息所用的语言回复（默认中文）。

## Markdown 格式规范
回复必须是合法 Markdown。注意：端侧不支持 LaTeX 数学公式与 Mermaid 图表，请勿使用。
- 用 # / ## / ### 标题划分结构，逐级递进不要跳级
- 重点内容用 **加粗**、*斜体*、~~删除线~~ 强调，克制使用
- 并列要点用无序列表（- ）；有顺序的步骤用有序列表（1. 2. 3.）；待办建议用任务列表（- [ ]）
- 代码一律放在围栏代码块中并标注语言（如 ```kotlin），行内代码用反引号包裹
- 重要提示或补充说明用 > 引用块
- 结构化对比数据用 Markdown 表格

## 排版与篇幅
手机屏幕较小且回复逐字流式显示：
- 先给结论，再展开细节；能用一句话说清的不要写成一段
- 段落简短，列表项尽量单行，列表嵌套不超过两层
- 代码块只保留必要片段，避免一次性输出超长代码

## 附件约定
用户消息中可能包含客户端注入的以下片段，均有明确含义：
- 【附件文件 xxx 内容】：用户上传文件的真实内容，请基于它回答
- 【图片附件：xxx（不支持图像输入）】：当前模型无法查看该图片，请如实说明并建议切换支持视觉的模型，不要假装看到了图片
- 【附件文件 xxx：读取失败/暂不支持解析】：文件内容不可用，请说明情况并建议改传文本类文件

## 基本原则
- 不确定的事实要明确说明不确定，不要编造
- 无法满足请求时简要说明原因，并尽量给出可行的替代方案
"""

    /**
     * Agent 模式追加的系统提示词：手机工具（tools）使用指引。
     * 与 [SYSTEM_PROMPT] 拼接后作为 Agent 会话的 system 消息。
     */
    const val AGENT_SYSTEM_PROMPT = """
## 工具使用（当前会话为 Agent 模式，可调用手机端工具）
可用工具覆盖：设备/系统信息、App 信息、屏幕/存储/内存/电池状态、清理缓存、蓝牙设备列表、
公共目录文件读取/写入/搜索、闹钟与定时器、联系人、剪贴板读写、拨号。
- 涉及手机真实状态或本地操作的请求（如"电量还剩多少""帮我设个 8 点的闹钟"），必须调用对应工具
  获取或执行，严禁凭想象回答或编造结果
- 一次回复中可按需调用多个工具；与手机无关的常识问题正常回答即可，不要强行调用工具
- 工具返回后基于真实结果作答：成功则整合为简洁结论；失败或未授权则如实告知原因与建议
- 拨号、写文件、清理缓存等会改动用户数据或设备的操作，完成后要在回复中明确说明已执行的动作与结果
"""

    // ==================== 普通聊天消息 ====================

    /**
     * 构造普通聊天请求体（OpenAI 格式）
     * @param messages 对话消息（user/assistant）
     * @param model    当前模型名（如 deepseek-chat）
     * @param tools    工具 schema（null 则不带 tools）
     */
    fun build(messages: List<AIChatMessage>, model: String, tools: JSONArray?): RequestBody {
        val startNs = System.nanoTime()
        val messagesArray = JSONArray()
        messagesArray.put(systemMessage(isAgent = false))
        for (msg in messages) {
            val role = if (msg.isUser()) "user" else "assistant"
            messagesArray.put(JSONObject().apply {
                put("role", role)
                put("content", buildMessageContent(msg))
            })
        }
        val body = toJsonBody(buildBody(messagesArray, model, tools))
        AppLog.d(AppLog.TAG_BUILD, "build(chat): model=$model, msgs=${messages.size}, tools=${tools?.length() ?: 0}, body=${AppLog.sizeText(body.contentLength().coerceAtLeast(0))}, ${AppLog.elapsedMs(startNs)}")
        return body
    }

    // ==================== Agent 消息 ====================

    /**
     * 构造 Agent 请求体（多轮上下文，含 tool 消息 / assistant tool_calls）
     * @param messages Agent 多轮消息（user/assistant/tool）
     */
    fun buildAgent(messages: List<AgentMessage>, model: String, tools: JSONArray?): RequestBody {
        val startNs = System.nanoTime()
        val messagesArray = JSONArray()
        messagesArray.put(systemMessage(isAgent = true))
        for (msg in messages) {
            when (msg.role) {
                "tool" -> messagesArray.put(JSONObject().apply {
                    put("role", "tool")
                    put("tool_call_id", msg.toolCallId ?: "")
                    put("content", msg.content)
                })

                "assistant" -> {
                    val obj = JSONObject().apply {
                        put("role", "assistant")
                        put("content", msg.content)
                    }
                    msg.toolCalls?.takeIf { it.isNotEmpty() }?.let { calls ->
                        obj.put("tool_calls", JSONArray().apply {
                            calls.forEach { tc ->
                                put(JSONObject().apply {
                                    put("id", tc.id)
                                    put("type", "function")
                                    put("function", JSONObject().apply {
                                        put("name", tc.name)
                                        put("arguments", tc.arguments)
                                    })
                                })
                            }
                        })
                    }
                    messagesArray.put(obj)
                }

                else -> messagesArray.put(JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                })
            }
        }
        val body = toJsonBody(buildBody(messagesArray, model, tools))
        AppLog.d(AppLog.TAG_BUILD, "build(agent): model=$model, msgs=${messages.size}(tool=${messages.count { it.role == "tool" }}), tools=${tools?.length() ?: 0}, body=${AppLog.sizeText(body.contentLength().coerceAtLeast(0))}, ${AppLog.elapsedMs(startNs)}")
        return body
    }

    // ==================== 内部构造 ====================

    /** system 消息：Agent 模式在基础提示词后追加手机工具使用指引 */
    private fun systemMessage(isAgent: Boolean): JSONObject = JSONObject().apply {
        put("role", "system")
        put("content", if (isAgent) SYSTEM_PROMPT + AGENT_SYSTEM_PROMPT else SYSTEM_PROMPT)
    }

    private fun buildBody(messagesArray: JSONArray, model: String, tools: JSONArray?): String {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", messagesArray)
            put("stream", true)
        }
        if (tools != null && tools.length() > 0) {
            body.put("tools", tools)
        }
        return body.toString()
    }

    /**
     * 根据 message 的附件情况构造 content：
     * - 无附件 / 无可传图片：纯文本字符串（最大兼容）
     * - 有图片且网关支持多模态：multimodal array（text + image_url(base64)）
     *
     * 兜底策略（保证 AI 总能收到附件信息并回复）：
     * - 图片 + DeepSeek 官方（无视觉模型）→ 文本说明占位，不发 image_url（否则 400）
     * - 文本类文件 → 读取内容作为文本片段
     * - 二进制/PDF 等不可解析文件 → 明确的占位说明（文件名/类型/大小）
     */
    fun buildMessageContent(msg: AIChatMessage): Any {
        // 1. 收集文本片段（消息正文 + 文件内容/占位说明）
        val texts = mutableListOf<String>()
        if (msg.content.isNotEmpty()) {
            texts.add(msg.content)
        }

        // 2. 图片单独收集（仅网关支持多模态时才组装 image_url）
        val imageParts = mutableListOf<JSONObject>()
        val multimodalSupported = !isDeepSeekOfficialGateway()

        for (att in msg.attachments) {
            if (att.isImage()) {
                val base64 = readLocalImageBase64(att.localPath)
                when {
                    base64 != null && multimodalSupported ->
                        imageParts.add(imageUrlPart(att.mimeType ?: "image/jpeg", base64))

                    base64 != null ->
                        texts.add(
                            "【图片附件：${att.fileName ?: "图片"}。当前模型（${AIConfig.model}）不支持图像输入，" +
                                "无法查看图片内容；如需识图请将 baseUrl/model 切换为支持视觉的模型】"
                        )

                    else ->
                        texts.add("【图片附件：${att.fileName ?: "图片"}。本地文件读取失败或超过 ${MAX_IMAGE_BYTES / (1024 * 1024)}MB，无法提供】")
                }
            } else {
                val ext = (att.fileName ?: "").substringAfterLast('.', "").toLowerCase()
                if (ext in TEXT_FILE_EXTS) {
                    val fileContent = readLocalFileText(att)
                    if (fileContent != null) {
                        texts.add("【附件文件 ${att.fileName ?: "未命名"} 内容】\n$fileContent")
                    } else {
                        texts.add("【附件文件 ${att.fileName ?: "未命名"}：读取失败或不存在】")
                    }
                } else {
                    texts.add(
                        "【附件文件 ${att.fileName ?: "未命名"}（${att.displayType} 类型，大小 ${formatSize(att.size)}）。" +
                            "该格式暂不支持解析为文本内容】"
                    )
                }
            }
        }

        // 3. 无可传图片 → 纯文本（DeepSeek 等纯文本模型完全兼容）
        if (msg.attachments.isNotEmpty()) {
            AppLog.d(AppLog.TAG_BUILD, "content: msgId=${msg.id}, attachments=${msg.attachments.size}, " +
                    "images(sent=${imageParts.size}), textParts=${texts.size}, " +
                    "multimodal=${imageParts.isNotEmpty()}")
        }
        if (imageParts.isEmpty()) {
            return texts.joinToString("\n")
        }
        // 4. 有图片 → multimodal array（text 片段 + image_url）
        val parts = JSONArray()
        if (texts.isNotEmpty()) {
            texts.forEach { parts.put(textPart(it)) }
        }
        imageParts.forEach { parts.put(it) }
        return parts
    }

    /** DeepSeek 官方网关（deepseek-chat/reasoner 为纯文本模型，不支持 image_url） */
    private fun isDeepSeekOfficialGateway(): Boolean =
        AIConfig.baseUrl.contains("deepseek.com", ignoreCase = true)

    /** 文本类文件大小格式化 */
    private fun formatSize(bytes: Long?): String {
        val v = bytes ?: 0L
        return when {
            v >= 1024 * 1024 -> "%.1fMB".format(v / (1024.0 * 1024.0))
            v >= 1024 -> "%.1fKB".format(v / 1024.0)
            else -> "${v}B"
        }
    }

    private fun textPart(text: String): JSONObject = JSONObject().apply {
        put("type", "text")
        put("text", text)
    }

    private fun imageUrlPart(mimeType: String, base64: String): JSONObject = JSONObject().apply {
        put("type", "image_url")
        put("image_url", JSONObject().apply {
            put("url", "data:$mimeType;base64,$base64")
        })
    }

    // ==================== 附件本地读取 ====================

    /**
     * 读取本地图片内容为 Base64（data URL 用）。
     * 超过 [MAX_IMAGE_BYTES] 或读取失败时返回 null（静默跳过该附件）。
     */
    private fun readLocalImageBase64(localPath: String?): String? {
        if (localPath.isNullOrEmpty()) return null
        return try {
            val file = File(localPath)
            if (!file.exists() || file.length() > MAX_IMAGE_BYTES) {
                AppLog.w(AppLog.TAG_ATTACH, "image skip: ${file.name}, exists=${file.exists()}, size=${AppLog.sizeText(file.length())}")
                return null
            }
            val bytes = file.readBytes()
            AppLog.d(AppLog.TAG_ATTACH, "image read: ${file.name}, ${AppLog.sizeText(bytes.size.toLong())} -> base64")
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            AppLog.w(AppLog.TAG_ATTACH, "readLocalImageBase64 error: ${e.message}")
            null
        }
    }

    /**
     * 读取本地文件文本内容（截断 [MAX_FILE_TEXT_LENGTH] 保护上下文）。
     * 非文本文件（二进制）读取异常时返回 null。
     */
    private fun readLocalFileText(att: Attachment): String? {
        val localPath = att.localPath
        if (localPath.isNullOrEmpty()) return null
        return try {
            val file = File(localPath)
            if (!file.exists()) {
                AppLog.w(AppLog.TAG_ATTACH, "file not found: ${att.fileName}")
                return null
            }
            val text = file.readText(Charsets.UTF_8)
            val clipped = if (text.length > MAX_FILE_TEXT_LENGTH) text.substring(0, MAX_FILE_TEXT_LENGTH) else text
            AppLog.d(AppLog.TAG_ATTACH, "file read: ${att.fileName}, ${text.length} chars${if (clipped.length < text.length) "(截断至 $MAX_FILE_TEXT_LENGTH)" else ""}")
            clipped
        } catch (e: Exception) {
            AppLog.w(AppLog.TAG_ATTACH, "readLocalFileText error: ${e.message}")
            null
        }
    }

    // ==================== 工具 ====================

    /** okhttp 3.9.0 无 toRequestBody 扩展，手动构造 RequestBody */
    private fun toJsonBody(json: String): RequestBody = object : RequestBody() {
        override fun contentType(): MediaType? = MediaType.parse("application/json; charset=utf-8")

        override fun writeTo(sink: BufferedSink) {
            sink.writeUtf8(json)
        }
    }
}