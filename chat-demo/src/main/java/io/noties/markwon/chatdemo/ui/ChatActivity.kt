package io.noties.markwon.chatdemo.ui

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.ViewModelProvider
import io.noties.markwon.chatdemo.R
import io.noties.markwon.chatdemo.bean.Attachment
import io.noties.markwon.chatdemo.bean.SessionSummary
import io.noties.markwon.chatdemo.service.AIConfig
import io.noties.markwon.chatdemo.tool.ToolPermissionManager
import io.noties.markwon.chatdemo.util.AppLog
import io.noties.markwon.chatdemo.util.FileHelper
import io.noties.markwon.chatdemo.viewmodel.ChatMessageItem
import io.noties.markwon.chatdemo.viewmodel.ChatViewModel
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 聊天页面（chat-demo）
 *
 * - 中部：消息列表（用户右蓝泡 / AI 左白泡 + Markdown 流式渲染）
 * - 左侧：DrawerLayout 滑出历史会话列表（新建对话 / 删除会话 / 切换会话）
 * - 底部：图片/文件附件 + 输入框 + 发送/停止
 * - 右上角：设置弹窗（baseUrl / apiKey / model，模型可切换，保存即生效）
 *
 * 同时实现 [ToolPermissionManager.Delegate]：Agent 工具需要运行时权限时，
 * 弹窗向用户说明用途 → 拉起系统授权 →（永久拒绝时）引导系统设置并复查。
 */
class ChatActivity : AppCompatActivity(), ChatViewModel.Callbacks, ToolPermissionManager.Delegate {

    companion object {
        /** 工具权限请求码起始（避开 FileHelper 的 1101/1102） */
        private const val REQ_PERMISSION_BASE = 2000

        /** 「去系统设置」请求码 */
        private const val REQ_OPEN_SETTINGS = 2999
    }

    private val viewModel: ChatViewModel by lazy {
        // ChatViewModel 为 AndroidViewModel（构造需 Application），
        // 老版本 activity/appcompat 默认工厂走 NewInstanceFactory（无参）会崩溃，显式指定 AndroidViewModelFactory
        ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory(application)
        ).get(ChatViewModel::class.java)
    }

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var rvMessages: RecyclerView
    private lateinit var rvSessions: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var btnSend: View
    private lateinit var btnStop: View
    private lateinit var btnAdd: View
    private lateinit var emptyView: View
    private lateinit var tvPillChat: TextView
    private lateinit var tvPillAgent: TextView
    private lateinit var llAttachmentPreview: View
    private lateinit var llAttachments: LinearLayout

    private lateinit var chatAdapter: ChatAdapter
    private lateinit var sessionAdapter: SessionAdapter

    /** 列表是否贴底：思考/回复流式输出时自动跟随贴底；用户上翻阅读历史时暂停，滚回底部自动恢复 */
    private var atBottom = true

    // ==================== 工具权限请求状态 ====================

    /** 挂起等待系统授权结果的 continuation（key=权限请求码） */
    private val permissionWaiters = mutableMapOf<Int, CancellableContinuation<Boolean>>()

    /** 下一个权限请求码 */
    private var permissionRequestCode = REQ_PERMISSION_BASE

    /** 挂起等待「从系统设置返回」后复查的 continuation 与对应权限 */
    private var settingsWaiter: CancellableContinuation<Boolean>? = null
    private var settingsPermission: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        viewModel.callbacks = this
        // 挂载工具权限委托（Agent 工具弹窗授权由本 Activity 承载）
        ToolPermissionManager.attach(this)

        drawerLayout = findViewById(R.id.drawer_layout)
        rvMessages = findViewById(R.id.rv_messages)
        rvSessions = findViewById(R.id.rv_sessions)
        etInput = findViewById(R.id.et_input)
        btnSend = findViewById(R.id.btn_send)
        btnStop = findViewById(R.id.btn_stop)
        btnAdd = findViewById(R.id.btn_add)
        emptyView = findViewById(R.id.empty_view)
        tvPillChat = findViewById(R.id.tv_pill_chat)
        tvPillAgent = findViewById(R.id.tv_pill_agent)
        llAttachmentPreview = findViewById(R.id.ll_attachment_preview)
        llAttachments = findViewById(R.id.ll_attachments)

        chatAdapter = ChatAdapter(viewModel)
        rvMessages.layoutManager = LinearLayoutManager(this)
        rvMessages.adapter = chatAdapter
        // notifyItemChanged 重绘去掉交叉渐变动画，流式期间避免整条消息闪烁
        (rvMessages.itemAnimator as? androidx.recyclerview.widget.DefaultItemAnimator)
            ?.supportsChangeAnimations = false
        // 跟踪用户滚动意图：贴底标记驱动流式自动跟随
        rvMessages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                atBottom = !recyclerView.canScrollVertically(1)
            }
        })

        sessionAdapter = SessionAdapter()
        rvSessions.layoutManager = LinearLayoutManager(this)
        rvSessions.adapter = sessionAdapter

        findViewById<View>(R.id.btn_drawer).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }
        findViewById<View>(R.id.btn_settings).setOnClickListener { showConfigDialog() }
        findViewById<View>(R.id.btn_new_chat).setOnClickListener {
            viewModel.createNewSession()
            etInput.setText("") // 新建会话时同步清空输入框
            drawerLayout.closeDrawers()
        }
        // 胶囊双段切换：聊天 / Agent
        tvPillChat.setOnClickListener {
            if (viewModel.agentEnabled && !viewModel.isStreaming) viewModel.toggleAgent()
        }
        tvPillAgent.setOnClickListener {
            if (!viewModel.agentEnabled && !viewModel.isStreaming) viewModel.toggleAgent()
        }

        // 附件入口：选择 图片 / 文件
        btnAdd.setOnClickListener { showAttachmentPicker() }
        btnSend.setOnClickListener { doSend() }
        btnStop.setOnClickListener { viewModel.stopClick() }

        etInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.inputText = s?.toString().orEmpty()
            }
        })
        // 键盘发送键直接发送
        etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                doSend()
                true
            } else {
                false
            }
        }

        // 软键盘弹出/收起时（输入框弹出来）消息列表贴底：adjustResize 下根视图高度变化
        // 超过屏幕 1/3 视为键盘状态切换，此时可见区域变化，底部最新消息应保持可见
        val rootView = findViewById<View>(android.R.id.content)
        var lastVisibleHeight = 0
        val maxShift = resources.displayMetrics.heightPixels / 3
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            val visibleHeight = rootView.height
            if (lastVisibleHeight == 0) {
                lastVisibleHeight = visibleHeight
                return@addOnGlobalLayoutListener
            }
            val diff = lastVisibleHeight - visibleHeight
            if (diff > maxShift || diff < -maxShift) {
                scrollToBottom()
            }
            lastVisibleHeight = visibleHeight
        }

        onInputUiChanged()
    }

    /**
     * 发送消息并清空输入框。
     * ViewModel 侧 [ChatViewModel.inputText] 已置空，但输入框仅通过 TextWatcher 单向同步 VM，
     * 需在此处显式清空 EditText 才能让界面文本同步消失。
     */
    private fun doSend() {
        if (viewModel.isStreaming) return
        val text = etInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() && viewModel.selectedAttachments.isEmpty()) return
        viewModel.sendTextMessage()
        etInput.setText("")
    }

    /** 附件选择入口（设计稿 add icon） */
    private fun showAttachmentPicker() {
        val options = arrayOf("选择图片", "选择文件")
        AlertDialog.Builder(this)
            .setTitle("添加附件")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> FileHelper.pickImage(this)
                    1 -> FileHelper.pickFile(this)
                }
            }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 解除回调，避免 ViewModel 中异步流式结束后访问已销毁的视图
        viewModel.callbacks = null
        // 解除工具权限委托；未决请求恢复为「拒绝」，避免工具协程永久挂起
        ToolPermissionManager.detach()
        resumePendingPermissionWaiters()
    }

    // ==================== 工具权限委托（ToolPermissionManager.Delegate） ====================

    /**
     * 弹出授权说明对话框 → 用户确认后拉起系统授权弹窗，挂起等待结果。
     * 用户点「暂不」直接返回拒绝；页面销毁/流式取消时 continuation 取消并关闭弹窗。
     */
    override suspend fun requestPermission(permission: String, toolName: String, reason: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val dialog = AlertDialog.Builder(this)
                .setTitle("AI 工具请求授权")
                .setMessage("工具「$toolName」需要以下权限：\n\n$reason\n\n是否现在授权？")
                .setCancelable(false)
                .setPositiveButton("授权") { _, _ ->
                    val code = permissionRequestCode++
                    permissionWaiters[code] = cont
                    ActivityCompat.requestPermissions(this, arrayOf(permission), code)
                }
                .setNegativeButton("暂不") { _, _ -> cont.resumeSafe(false) }
                .show()
            cont.invokeOnCancellation {
                // 流式被停止/页面销毁：关闭弹窗并清理登记
                runCatching { dialog.dismiss() }
                permissionWaiters.entries.removeAll { it.value === cont }
                if (settingsWaiter === cont) {
                    settingsWaiter = null
                    settingsPermission = null
                }
            }
        }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        val cont = permissionWaiters.remove(requestCode)
        if (cont == null) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            return
        }
        val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (granted) {
            cont.resumeSafe(true)
            return
        }
        // 拒绝：被「不再询问」时引导去系统设置，否则视为用户拒绝
        val permission = permissions.firstOrNull().orEmpty()
        if (!ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
            showPermissionSettingsDialog(cont, permission)
        } else {
            cont.resumeSafe(false)
        }
    }

    /** 永久拒绝：引导用户前往系统设置手动开启，返回后复查权限 */
    private fun showPermissionSettingsDialog(cont: CancellableContinuation<Boolean>, permission: String) {
        AlertDialog.Builder(this)
            .setTitle("需要手动开启权限")
            .setMessage("该权限已被设为「不再询问」，需要前往：\n\n系统设置 → 应用 → chat-demo → 权限\n\n手动开启后返回即可继续。")
            .setCancelable(false)
            .setPositiveButton("去设置") { _, _ ->
                settingsWaiter = cont
                settingsPermission = permission
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivityForResult(intent, REQ_OPEN_SETTINGS)
            }
            .setNegativeButton("取消") { _, _ -> cont.resumeSafe(false) }
            .show()
    }

    /** 页面销毁：未决的权限等待统一恢复为拒绝 */
    private fun resumePendingPermissionWaiters() {
        permissionWaiters.values.forEach { it.resumeSafe(false) }
        permissionWaiters.clear()
        settingsWaiter?.resumeSafe(false)
        settingsWaiter = null
        settingsPermission = null
    }

    /** resume 防抖：已取消/已恢复的 continuation 静默忽略 */
    private fun CancellableContinuation<Boolean>.resumeSafe(value: Boolean) {
        if (isActive) {
            runCatching { resume(value) }
        }
    }

    // ==================== 模型配置弹窗 ====================

    private fun showConfigDialog() {
        val content = LayoutInflater.from(this).inflate(R.layout.dialog_model_config, null)
        val etBaseUrl = content.findViewById<EditText>(R.id.et_base_url)
        val etApiKey = content.findViewById<EditText>(R.id.et_api_key)
        val etModel = content.findViewById<EditText>(R.id.et_model)
        val spinnerLogLevel = content.findViewById<android.widget.Spinner>(R.id.spinner_log_level)
        etBaseUrl.setText(AIConfig.baseUrl)
        etApiKey.setText(AIConfig.apiKey)
        etModel.setText(AIConfig.model)

        // 日志级别 Spinner：即时生效并持久化
        val levels = AppLog.Level.values()
        spinnerLogLevel.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, levels.toList()
        )
        spinnerLogLevel.setSelection(levels.indexOf(AppLog.minLevel))
        spinnerLogLevel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                AppLog.setLevel(this@ChatActivity, levels[position])
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        AlertDialog.Builder(this)
            .setTitle("模型配置")
            .setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                viewModel.saveAIConfig(
                    etBaseUrl.text.toString(),
                    etApiKey.text.toString(),
                    etModel.text.toString()
                )
                Toast.makeText(this, "已保存，下次请求生效", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ==================== onActivityResult（附件选择 / 权限设置页返回） ====================

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // 从系统设置页返回：复查权限并恢复挂起的工具
        if (requestCode == REQ_OPEN_SETTINGS) {
            val cont = settingsWaiter
            val permission = settingsPermission
            settingsWaiter = null
            settingsPermission = null
            if (cont != null && permission != null) {
                val granted = ContextCompat.checkSelfPermission(this, permission) ==
                        PackageManager.PERMISSION_GRANTED
                cont.resumeSafe(granted)
            }
            return
        }
        val attachments = FileHelper.onActivityResult(this, requestCode, resultCode, data)
        attachments.forEach { viewModel.addAttachment(it) }
    }

    // ==================== ViewModel.Callbacks 实现 ====================

    override fun onMessageListChanged() {
        chatAdapter.notifyDataSetChanged()
        // 空态欢迎语显隐（对标设计稿"首页-无消息"）
        emptyView.visibility =
            if (viewModel.messages.isEmpty()) View.VISIBLE else View.GONE
        // 列表结构变化（发送消息/切换会话/移除占位）后贴底
        scrollToBottom()
    }

    override fun onMessageContentChanged(item: ChatMessageItem, delta: String) {
        chatAdapter.onContentChanged(item, delta)
        // 流式输出全程贴底（用户上翻阅读历史时暂停跟随）
        stickToBottom()
    }

    override fun onThinkingChanged(item: ChatMessageItem, delta: String) {
        chatAdapter.onThinkingChanged(item, delta)
        // 思考过程增长同样贴底，保证最新思考内容可见
        stickToBottom()
    }

    override fun onMessageStateChanged(item: ChatMessageItem) {
        chatAdapter.onStateChanged(item)
        // 状态翻转（loading→streaming / Agent 步骤刷新 / 流结束）时贴底
        stickToBottom()
    }

    override fun onSessionListChanged() {
        sessionAdapter.notifyDataSetChanged()
    }

    override fun onAttachmentsChanged() {
        rebuildAttachmentPreview()
    }

    override fun onInputUiChanged() {
        val streaming = viewModel.isStreaming
        val canSend = viewModel.canSend
        // 生成中：显示停止 icon；否则有内容才显示发送
        btnSend.visibility = if (!streaming && canSend) View.VISIBLE else View.GONE
        btnStop.visibility = if (streaming) View.VISIBLE else View.GONE

        // 胶囊双段选中态（对标设计稿：选中块 #6674FF 白字）
        val agentOn = viewModel.agentEnabled
        tvPillChat.background = if (agentOn) null else getDrawableCompat(R.drawable.bg_pill_selected)
        tvPillChat.setTextColor(if (agentOn) 0xFF181818.toInt() else 0xFFFFFFFF.toInt())
        tvPillAgent.background = if (agentOn) getDrawableCompat(R.drawable.bg_pill_selected) else null
        tvPillAgent.setTextColor(if (agentOn) 0xFFFFFFFF.toInt() else 0xFF181818.toInt())
    }

    private fun getDrawableCompat(resId: Int) =
        androidx.core.content.ContextCompat.getDrawable(this, resId)

    override fun scrollToBottom() {
        if (viewModel.messages.isNotEmpty()) {
            // post：等待本次数据变化的 layout 完成后精确贴底
            rvMessages.post { pinListToBottom() }
        }
    }

    /** 流式贴底：仅在用户已处于底部时跟随（上翻阅读历史时不打扰） */
    private fun stickToBottom() {
        if (!atBottom) return
        // post：内容增量触发的 requestLayout 完成后，(itemView.bottom 已更新) 再精确贴底
        rvMessages.post { pinListToBottom() }
    }

    /**
     * 精确贴底：
     * - 最后一条已可见 → scrollBy 恰好压齐其底部（打字机新增内容持续可见，
     *   兼容最后一条比屏幕高、scrollToPosition 只能顶到其顶部的场景）；
     * - 不可见（刚插入/会话切换）→ scrollToPosition 滚过去后再压齐一次。
     */
    private fun pinListToBottom() {
        val count = viewModel.messages.size
        if (count == 0) return
        val holder = rvMessages.findViewHolderForAdapterPosition(count - 1)
        if (holder != null) {
            val over = holder.itemView.bottom - (rvMessages.height - rvMessages.paddingBottom)
            if (over > 0) {
                rvMessages.scrollBy(0, over)
            }
        } else {
            rvMessages.scrollToPosition(count - 1)
            rvMessages.post {
                val last = rvMessages.findViewHolderForAdapterPosition(viewModel.messages.size - 1)
                    ?: return@post
                val over = last.itemView.bottom - (rvMessages.height - rvMessages.paddingBottom)
                if (over > 0) {
                    rvMessages.scrollBy(0, over)
                }
            }
        }
    }

    override fun onError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ==================== 附件预览条 ====================

    private fun rebuildAttachmentPreview() {
        llAttachments.removeAllViews()
        val list = viewModel.selectedAttachments
        if (list.isEmpty()) {
            llAttachmentPreview.visibility = View.GONE
            return
        }
        llAttachmentPreview.visibility = View.VISIBLE
        list.forEach { att -> llAttachments.addView(createAttachmentChip(att)) }
    }

    private fun createAttachmentChip(att: Attachment): View {
        val chip = LayoutInflater.from(this).inflate(R.layout.item_chat_attachment_chip, llAttachments, false)
        val ivIcon = chip.findViewById<ImageView>(R.id.iv_chip_icon)
        val tvName = chip.findViewById<TextView>(R.id.tv_chip_name)
        // 注意：关闭按钮是 TextView（此前误用 ImageView 泛型导致 ClassCastException 闪退）
        val tvClose = chip.findViewById<TextView>(R.id.tv_chip_close)

        if (att.isImage()) {
            val bitmap = FileHelper.decodeThumb(att.localPath, 48)
            if (bitmap != null) ivIcon.setImageBitmap(bitmap) else ivIcon.setImageResource(R.drawable.ic_image_placeholder)
        } else {
            ivIcon.setImageResource(R.drawable.ic_file)
        }
        tvName.text = att.fileName ?: "文件"
        tvClose.setOnClickListener { viewModel.removeAttachment(att) }
        return chip
    }

    // ==================== 会话列表 Adapter ====================

    private inner class SessionAdapter : RecyclerView.Adapter<SessionAdapter.Holder>() {

        private val dateFormat = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_session_summary, parent, false)
        )

        override fun getItemCount(): Int = viewModel.sessionList.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val session = viewModel.sessionList[position]
            holder.title.text = session.title.ifBlank { "新对话" }
            holder.last.text = session.lastMessage
            holder.time.text = dateFormat.format(Date(session.timestamp))
            holder.itemView.setBackgroundResource(
                if (session.isSelected) R.drawable.bg_session_selected else R.drawable.bg_session_normal
            )
            holder.itemView.setOnClickListener {
                viewModel.switchSession(session.sessionId)
                drawerLayout.closeDrawers()
            }
            holder.delete.setOnClickListener {
                // 删除不可恢复：二次确认
                AlertDialog.Builder(this@ChatActivity)
                    .setTitle("删除会话")
                    .setMessage("确定删除「${session.title.ifBlank { "新对话" }}」吗？\n该会话的全部聊天记录将被清除，且不可恢复。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除") { _, _ ->
                        AppLog.i(AppLog.TAG_DB, "delete session: ${session.sessionId}")
                        viewModel.deleteSession(session.sessionId)
                    }
                    .show()
            }
        }

        inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val title: TextView = itemView.findViewById(R.id.tv_session_title)
            val last: TextView = itemView.findViewById(R.id.tv_session_last)
            val time: TextView = itemView.findViewById(R.id.tv_session_time)
            val delete: View = itemView.findViewById(R.id.btn_session_delete)
        }
    }
}