package io.noties.markwon.chatdemo.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import io.noties.markwon.block.render.OnLinkClick
import io.noties.markwon.block.view.MarkdownTextBlockView
import io.noties.markwon.chatdemo.R
import io.noties.markwon.chatdemo.bean.AIChatMessage
import io.noties.markwon.chatdemo.bean.AgentStep
import io.noties.markwon.chatdemo.util.FileHelper
import io.noties.markwon.chatdemo.util.AppLog
import io.noties.markwon.chatdemo.viewmodel.ChatMessageItem
import io.noties.markwon.chatdemo.viewmodel.ChatTrailSegment
import io.noties.markwon.chatdemo.viewmodel.ChatViewModel

/**
 * 聊天消息列表 Adapter（手写，无 DataBinding）
 *
 * 两种视图类型：
 * - [TYPE_USER]：右侧蓝泡，支持文本 + 图片缩略图 / 文件 chip
 * - [TYPE_AI]：左侧白泡，MarkdownTextBlockView 渲染；流式阶段增量 appendMarkdown
 *   实现打字机效果；思考阶段显示 ThinkingDotsView；提供复制/重试按钮
 *
 * AI 轨迹渲染（[ChatTrailSegment]）：思考段面板与工具步骤卡片按模型输出顺序交替挂载
 * 到 [ll_trail_container]，多轮 Agent 显示为「思考 → 工具 → 思考 → 工具 → 文本」的连贯顺序；
 * 流式时思考文本增量 append（不整块重绘），工具卡片状态变化原位更新。
 */
class ChatAdapter(
    private val viewModel: ChatViewModel
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_AI = 1

        /** AI 气泡内图片缩略图最大边长（dp） */
        private const val MAX_THUMB_DP = 160

        /** 单段思考内容区最大高度（dp）：超过后该段内部滚动，避免长思考撑爆气泡 */
        private const val THINKING_MAX_HEIGHT_DP = 200
    }

    private var inflater: LayoutInflater? = null
    private var recyclerView: RecyclerView? = null

    private fun getInflater(context: Context): LayoutInflater =
        inflater ?: LayoutInflater.from(context).also { inflater = it }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        this.recyclerView = null
    }

    override fun getItemViewType(position: Int): Int {
        val item = viewModel.messages[position]
        return if (item.message.isUser()) TYPE_USER else TYPE_AI
    }

    override fun getItemCount(): Int = viewModel.messages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val context = parent.context
        return when (viewType) {
            TYPE_USER -> UserViewHolder(
                getInflater(context).inflate(R.layout.item_message_user_text, parent, false)
            )
            else -> AiViewHolder(
                getInflater(context).inflate(R.layout.item_message_ai_text, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = viewModel.messages[position]
        when (holder) {
            is UserViewHolder -> holder.bind(item)
            is AiViewHolder -> holder.bind(item)
        }
    }

    // ==================== 流式增量回调（ViewModel → Adapter） ====================

    /**
     * 单条消息内容变化：
     * - [delta] 非空且目标 AI holder 已绑定并处于「视图与数据同步」状态 → 增量 appendMarkdown
     * - 其他情况（holder 未附着 / 被复用 / 存在 pending 重绘竞争）→ notifyItemChanged 整条重绘
     *   （重绘渲染全量 item.content，保证视图永远与数据一致）
     */
    fun onContentChanged(item: ChatMessageItem, delta: String) {
        val pos = viewModel.messages.indexOf(item)
        if (pos < 0) return
        if (delta.isEmpty()) {
            AppLog.d(AppLog.TAG_RENDER, "full rebind pos=$pos (final/sync render)")
            notifyItemChanged(pos)
            return
        }
        val rv = recyclerView ?: run {
            AppLog.w(AppLog.TAG_RENDER, "recyclerView detached, full rebind pos=$pos")
            notifyItemChanged(pos)
            return
        }
        val holder = rv.findViewHolderForAdapterPosition(pos) as? AiViewHolder
        if (holder != null && holder.holderItem === item && holder.tryAppend(delta)) {
            return
        }
        // 增量失配（holder 未附着/被复用/同步校验失败）→ 整条重绘保证一致性
        AppLog.w(AppLog.TAG_RENDER, "append miss pos=$pos, deltaLen=${delta.length}, holderHit=${holder != null}, sameItem=${holder?.holderItem === item}")
        notifyItemChanged(pos)
    }

    /**
     * 单条消息状态变化（loading/streaming/status/工具步骤状态）：
     * 已绑定同 item 的 AI holder 直接原位更新（不整条重绘，避免打断流式渲染），否则整条重绘
     */
    fun onStateChanged(item: ChatMessageItem) {
        val pos = viewModel.messages.indexOf(item)
        if (pos < 0) return
        val rv = recyclerView ?: run {
            notifyItemChanged(pos)
            return
        }
        val holder = rv.findViewHolderForAdapterPosition(pos) as? AiViewHolder
        if (holder != null && holder.holderItem === item) {
            holder.updateState()
        } else {
            notifyItemChanged(pos)
        }
    }

    /**
     * 思考过程增量（reasoning_content）：
     * 命中已绑定同 item 的 AI holder 直接文字追加（定位到当前思考段），否则整条重绘
     */
    fun onThinkingChanged(item: ChatMessageItem, delta: String) {
        val pos = viewModel.messages.indexOf(item)
        if (pos < 0) return
        val rv = recyclerView ?: run {
            AppLog.w(AppLog.TAG_RENDER, "thinking: rv detached, full rebind pos=$pos")
            notifyItemChanged(pos)
            return
        }
        val holder = rv.findViewHolderForAdapterPosition(pos) as? AiViewHolder
        if (holder != null && holder.holderItem === item && holder.tryAppendThinking(delta)) {
            return
        }
        AppLog.w(AppLog.TAG_RENDER, "thinking append miss pos=$pos, holderHit=${holder != null}")
        notifyItemChanged(pos)
    }

    // ==================== 用户消息 ====================

    inner class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvContent: TextView = itemView.findViewById(R.id.tv_user_text)
        private val llImages: LinearLayout = itemView.findViewById(R.id.ll_user_images)
        private val tvFile: TextView = itemView.findViewById(R.id.tv_user_file)
        private val ivFailed: ImageView = itemView.findViewById(R.id.iv_user_failed)

        fun bind(item: ChatMessageItem) {
            val message = item.message
            val context = itemView.context

            tvContent.visibility = if (message.content.isBlank()) View.GONE else View.VISIBLE
            tvContent.text = message.content

            bindAttachments(message, context)

            // 发送失败：气泡左侧显示红圈感叹号（设计稿状态色 #FF5555），点击重发
            val failed = message.isUser() && message.status == AIChatMessage.STATUS_FAILED
            ivFailed.visibility = if (failed) View.VISIBLE else View.GONE
            ivFailed.setOnClickListener { viewModel.retryMessage(item) }
        }

        private fun bindAttachments(message: AIChatMessage, context: Context) {
            val images = message.attachments.filter { it.isImage() }
            val files = message.attachments.filter { !it.isImage() }

            llImages.removeAllViews()
            if (images.isNotEmpty()) {
                llImages.visibility = View.VISIBLE
                val thumbPx = dp(context, MAX_THUMB_DP.toFloat())
                val gapPx = dp(context, 6f)
                images.take(4).forEach { att ->
                    val imageView = ImageView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(thumbPx, thumbPx).apply {
                            marginEnd = gapPx
                        }
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        background = context.getDrawable(R.drawable.bg_img_thumb)
                    }
                    val bitmap = FileHelper.decodeThumb(att.localPath, thumbPx)
                    if (bitmap != null) {
                        imageView.setImageBitmap(bitmap)
                    } else {
                        imageView.setImageResource(R.drawable.ic_image_placeholder)
                    }
                    llImages.addView(imageView)
                }
            } else {
                llImages.visibility = View.GONE
            }

            if (files.isNotEmpty()) {
                tvFile.visibility = View.VISIBLE
                tvFile.text = files.first().fileName ?: "文件"
            } else {
                tvFile.visibility = View.GONE
            }
        }
    }

    // ==================== AI 消息 ====================

    inner class AiViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val dots: ThinkingDotsView = itemView.findViewById(R.id.thinking_dots)
        private val markdownView: MarkdownTextBlockView = itemView.findViewById(R.id.ai_markdown_view)
        // 反馈操作栏（设计稿：分割线 + 复制/重新生成图标按钮）
        private val llAiFeedback: LinearLayout = itemView.findViewById(R.id.ll_ai_feedback)
        private val ivFeedbackCopy: ImageView = itemView.findViewById(R.id.iv_feedback_copy)
        private val ivFeedbackRegenerate: ImageView = itemView.findViewById(R.id.iv_feedback_regenerate)

        /** 渲染轨迹容器：思考段面板 + 工具步骤卡片按 [ChatTrailSegment] 顺序动态挂载 */
        private val llTrail: LinearLayout = itemView.findViewById(R.id.ll_trail_container)

        /** 当前绑定 item（校验流式增量是否仍指向同一 item，防止复用错位） */
        var holderItem: ChatMessageItem? = null

        /** 视图已累积渲染的字符长度（bind 全量渲染 / append 增量累加） */
        private var viewSyncedLength: Int = 0

        /** 思考全文已累积渲染长度（流式追加用，跨段连续累计） */
        private var viewSyncedThinkingLength: Int = 0

        /** trail 段视图绑定（下标与 item.trail 一一对应） */
        private inner class ThinkingBinding(
            var expanded: Boolean = false,
            var userTouch: Boolean = false,
            var programmatic: Boolean = false,
            var isFirstChunk: Boolean = false
        )

        private inner class TrailViewBinding {
            var seg: ChatTrailSegment? = null
            var thinking: ThinkingBinding? = null
            var thinkingView: ThinkingPanelView? = null
            var toolsContainer: LinearLayout? = null
        }

        private inner class ThinkingPanelView(
            val toggle: LinearLayout,
            val arrow: TextView,
            val sv: MaxHeightNestedScrollView,
            val tv: TextView
        )

        private val trailBindings = mutableListOf<TrailViewBinding>()

        private val linkClickListener = OnLinkClick { url ->
            val ctx = itemView.context
            val opened = try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                true
            } catch (e: Exception) {
                false
            }
            if (!opened) {
                copyText(url)
                Toast.makeText(ctx, "已复制链接", Toast.LENGTH_SHORT).show()
            }
        }

        fun bind(item: ChatMessageItem) {
            holderItem = item
            AppLog.v(AppLog.TAG_RENDER, "AiViewHolder.bind pos=$adapterPosition, contentLen=${item.content.length}, trail=${item.trail.size}")
            renderTrail()
            updateState(render = true)
        }

        // ---------- trail 渲染 ----------

        /** 全量渲染轨迹：清空容器后按顺序渲染所有段（bind / 整条重绘） */
        private fun renderTrail() {
            val item = holderItem ?: return
            llTrail.removeAllViews()
            trailBindings.clear()
            item.trail.forEach { seg ->
                when (seg) {
                    is ChatTrailSegment.Thinking -> inflateThinkingPanel(seg)
                    is ChatTrailSegment.Tools -> inflateToolsSegment(seg)
                }
            }
            // 容器有内容时显示，无内容时隐藏
            llTrail.visibility = if (item.trail.isEmpty()) View.GONE else View.VISIBLE
            // 思考全文长度与已渲染长度对齐（后续 append 增量校验）
            viewSyncedThinkingLength = item.thinking.length
        }

        /**
         * 增量挂载新出现的 trail 段（流式中新思考段 / 新工具段）：
         * 只渲染 trailBindings 之后的部分，已有段不重建，避免打断流式。
         */
        private fun ensureTrailViews() {
            val item = holderItem ?: return
            var i = trailBindings.size
            while (i < item.trail.size) {
                when (val seg = item.trail[i]) {
                    is ChatTrailSegment.Thinking -> inflateThinkingPanel(seg)
                    is ChatTrailSegment.Tools -> inflateToolsSegment(seg)
                }
                i++
            }
            // 流式中新段挂载后显示容器
            if (item.trail.isNotEmpty()) llTrail.visibility = View.VISIBLE
        }

        /** inflate 一段思考面板（可折叠 + 内部滚动 + 流式跟随） */
        private fun inflateThinkingPanel(seg: ChatTrailSegment.Thinking) {
            val view = getInflater(itemView.context)
                .inflate(R.layout.layout_trail_thinking_panel, llTrail, false)
            val toggle = view.findViewById<LinearLayout>(R.id.trail_think_toggle)
            val arrow = view.findViewById<TextView>(R.id.trail_think_arrow)
            val sv = view.findViewById<MaxHeightNestedScrollView>(R.id.trail_think_scroll)
            val tv = view.findViewById<TextView>(R.id.trail_think_text)

            sv.maxHeight = dp(itemView.context, THINKING_MAX_HEIGHT_DP.toFloat())
            sv.isSmoothScrollingEnabled = false
            val binding = ThinkingBinding()
            toggle.setOnClickListener { toggleThinking(binding) }
            sv.setOnScrollChangedCallback { _, _, _, _ ->
                // 仅用户滚动参与自动跟随判定（程序化滚动由 programmatic 标记排除）
                if (!binding.programmatic) {
                    binding.userTouch = sv.canScrollVertically(1)
                }
            }

            tv.text = seg.text
            // 流式中自动展开以便新思考可见；结束后默认收起，用户按段点击展开
            val autoExpand = holderItem?.loading == true || holderItem?.streaming == true
            binding.expanded = autoExpand
            binding.userTouch = false
            binding.isFirstChunk = seg.text.isEmpty()
            sv.visibility = if (autoExpand) View.VISIBLE else View.GONE
            arrow.text = if (autoExpand) "▾" else "▸"
            llTrail.addView(view)
            trailBindings.add(TrailViewBinding().apply {
                this.seg = seg
                this.thinking = binding
                this.thinkingView = ThinkingPanelView(toggle, arrow, sv, tv)
            })
            if (autoExpand) syncThinkingScroll(binding)
        }

        /** inflate 一段工具步骤段：Sequence 卡片逐个挂载到独立容器（与其他段保持分组） */
        private fun inflateToolsSegment(seg: ChatTrailSegment.Tools) {
            val container = LinearLayout(itemView.context).apply {
                orientation = LinearLayout.VERTICAL
            }
            llTrail.addView(container)
            trailBindings.add(TrailViewBinding().apply {
                this.seg = seg
                this.toolsContainer = container
            })
            seg.steps.forEach { step -> container.addView(buildStepCard(step)) }
        }

        /** 构建单张工具步骤卡片 */
        private fun buildStepCard(step: AgentStep): View {
            val card = getInflater(itemView.context)
                .inflate(R.layout.item_agent_step, llTrail, false)
            val tvTitle = card.findViewById<TextView>(R.id.tv_step_title)
            val tvStatus = card.findViewById<TextView>(R.id.tv_step_status)
            val tvSummary = card.findViewById<TextView>(R.id.tv_step_summary)

            tvTitle.text = step.title
            tvStatus.text = step.statusText()
            tvStatus.setTextColor(
                ContextCompat.getColor(
                    itemView.context,
                    if (step.isFailed()) android.R.color.holo_red_dark else android.R.color.darker_gray
                )
            )
            if (step.summary.isNotBlank()) {
                tvSummary.visibility = View.VISIBLE
                tvSummary.text = step.summary
            } else {
                tvSummary.visibility = View.GONE
            }
            return card
        }

        /**
         * 工具步骤状态原位更新：只重建 Tools 段内卡片（状态翻转：执行中→完成/失败），
         * 不触碰思考段，避免打断流式思考文本。
         */
        private fun updateTrailTools() {
            ensureTrailViews()
            trailBindings.forEach { tb ->
                val seg = tb.seg as? ChatTrailSegment.Tools ?: return@forEach
                val container = tb.toolsContainer ?: return@forEach
                container.removeAllViews()
                seg.steps.forEach { step -> container.addView(buildStepCard(step)) }
            }
        }

        /** 展开/收起某段思考面板 */
        private fun toggleThinking(binding: ThinkingBinding) {
            val tb = trailBindings.firstOrNull { it.thinking === binding }?.thinkingView ?: return
            binding.expanded = !binding.expanded
            tb.sv.visibility = if (binding.expanded) View.VISIBLE else View.GONE
            tb.arrow.text = if (binding.expanded) "▾" else "▸"
            if (binding.expanded) syncThinkingScroll(binding)
        }

        /** 思考滚动定位：流式进行中展开 → 底部（最新内容）；静态消息展开 → 顶部 */
        private fun syncThinkingScroll(binding: ThinkingBinding) {
            val tb = trailBindingFromBinding(binding)?.thinkingView ?: return
            tb.sv.post {
                val item = holderItem
                if (item != null && (item.loading || item.streaming)) {
                    thinkingScrollToBottom(binding)
                } else {
                    thinkingScrollToTop(binding)
                }
            }
        }

        private fun trailBindingFromBinding(binding: ThinkingBinding): TrailViewBinding? =
            trailBindings.firstOrNull { it.thinking === binding }

        /** 程序化滚动到底部（某段最新思考内容） */
        private fun thinkingScrollToBottom(binding: ThinkingBinding) {
            val tb = trailBindingFromBinding(binding)?.thinkingView ?: return
            binding.programmatic = true
            try {
                tb.sv.fullScroll(View.FOCUS_DOWN)
            } finally {
                binding.programmatic = false
            }
        }

        /** 程序化滚动回顶部（某段） */
        private fun thinkingScrollToTop(binding: ThinkingBinding) {
            val tb = trailBindingFromBinding(binding)?.thinkingView ?: return
            binding.programmatic = true
            try {
                tb.sv.scrollTo(0, 0)
            } finally {
                binding.programmatic = false
            }
        }

        // ---------- 状态 ----------

        /** 更新状态显隐；[render] 为 true 时整段渲染内容（bind 场景） */
        fun updateState(render: Boolean = false) {
            val item = holderItem ?: return
            val thinking = item.loading && item.content.isEmpty()

            if (!render) {
                // 非 bind 场景（loading/streaming/status/工具步骤变化）：原位更新工具卡片
                updateTrailTools()
            }

            dots.visibility = if (thinking) View.VISIBLE else View.GONE
            markdownView.visibility = if (thinking) View.GONE else View.VISIBLE
            if (render && !thinking) {
                markdownView.renderMarkdown(item.content, linkClickListener)
            }
            viewSyncedLength = if (thinking) 0 else item.content.length

            // 反馈操作栏：内容就绪（非思考中且非空）时展示复制/重新生成
            val showFeedback = !thinking && item.content.isNotEmpty()
            llAiFeedback.visibility = if (showFeedback) View.VISIBLE else View.GONE
            ivFeedbackCopy.setOnClickListener {
                copyText(item.content)
                Toast.makeText(itemView.context, "已复制", Toast.LENGTH_SHORT).show()
            }
            ivFeedbackRegenerate.setOnClickListener { viewModel.retryMessage(item) }
        }

        /**
         * 思考过程增量追加（流式）。
         * 定位到当前尾部思考段视图；新段首片段到达时自动展开；段内用户未上翻时滚动跟随最新内容。
         */
        fun tryAppendThinking(delta: String): Boolean {
            val item = holderItem ?: return false
            if (delta.isEmpty()) return false
            if (item.thinking.length != viewSyncedThinkingLength + delta.length) return false
            // 新段（本轮新思考/工具后首思考）未挂载视图时先增量挂载
            ensureTrailViews()
            val seg = item.trail.lastOrNull { it is ChatTrailSegment.Thinking } ?: return false
            val idx = item.trail.indexOf(seg)
            if (idx < 0 || idx >= trailBindings.size) return false
            val tb = trailBindings[idx]
            val binding = tb.thinking ?: return false
            val view = tb.thinkingView ?: return false

            if (binding.isFirstChunk) {
                // 该段首个片段到达：自动展开并随流滚动
                binding.isFirstChunk = false
                binding.expanded = true
                view.sv.visibility = View.VISIBLE
                view.arrow.text = "▾"
            }
            view.tv.append(delta)
            viewSyncedThinkingLength += delta.length
            if (binding.expanded && !binding.userTouch) {
                view.sv.post { thinkingScrollToBottom(binding) }
            }
            return true
        }

        /**
         * 流式 chunk 增量：视图与数据同步时才 appendMarkdown（打字机效果）。
         * 不同步（存在 pending 重绘竞争/复用错位）时返回 false，由 Adapter 走整条重绘。
         */
        fun tryAppend(delta: String): Boolean {
            val item = holderItem ?: return false
            if (item.content.isEmpty()) return false
            if (item.content.length != viewSyncedLength + delta.length) return false
            markdownView.visibility = View.VISIBLE
            dots.visibility = View.GONE
            markdownView.appendMarkdown(delta, linkClickListener, true)
            viewSyncedLength += delta.length
            llAiFeedback.visibility = View.VISIBLE
            return true
        }

        private fun copyText(text: String) {
            val cm = itemView.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ai_reply", text))
        }
    }

    private fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics
        ).toInt()
}