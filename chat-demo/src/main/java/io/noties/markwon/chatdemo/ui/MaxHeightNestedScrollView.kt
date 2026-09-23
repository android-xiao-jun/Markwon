package io.noties.markwon.chatdemo.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.core.widget.NestedScrollView

import io.noties.markwon.chatdemo.R

/**
 * 支持最大高度限制的 NestedScrollView（用于 AI 思考过程面板）。
 *
 * 为什么选 NestedScrollView 而不是普通 ScrollView：普通 ScrollView 没有实现嵌套滚动链路，
 * 在里面滑动到底部时，外层的 RecyclerView 无法直接接管同一根手指的滑动增量（fling/drag 会断掉）；
 * NestedScrollView 内部通过 dispatchNestedScroll 处理未消耗的偏移量（unconsumed delta），
 * 能实现顺滑连续的滚动传递——内容区优先滚动，滚到顶/底边界后剩余手势无缝交给外层列表。
 *
 * 注意：内层 TextView 上不要设置 ScrollingMovementMethod，否则手势会被 TextView 自身拦截，
 * 无法向上分发给 NestedScrollView。
 *
 * 内容不足时 wrap_content，超过 [maxHeight] 后高度封顶、内部滚动。
 * ScrollView 本身没有 maxHeight 属性，这里在 onMeasure 阶段把高度测量规格降级为 AT_MOST(maxHeight)。
 */
class MaxHeightNestedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {

    /** 滚动位置变化回调（用于区分用户/程序化滚动，判定流式自动跟随是否暂停） */
    fun interface OnScrollChangedCallback {
        fun onScrollChanged(scrollX: Int, scrollY: Int, oldScrollX: Int, oldScrollY: Int)
    }

    /** 最大高度（px），-1 表示不限制 */
    var maxHeight: Int = -1
        set(value) {
            field = value
            requestLayout()
        }

    /** 滚动位置变化回调 */
    private var onScrollChangedCallback: OnScrollChangedCallback? = null

    fun setOnScrollChangedCallback(callback: OnScrollChangedCallback?) {
        onScrollChangedCallback = callback
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onScrollChangedCallback?.onScrollChanged(l, t, oldl, oldt)
    }

    /** 上一次触摸 Y（计算拖动方向，判定该方向内部是否还有内容可滚） */
    private var lastTouchY = 0f

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val action = ev.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchY = ev.y
                // 内部可滚时才锁触摸给本容器，防止外层 RecyclerView 在嵌套滚动
                // pre-scroll 阶段抢先滚动整个列表（内部永远滚不动）
                val scrollable = canScrollVertically(1) || canScrollVertically(-1)
                parent?.requestDisallowInterceptTouchEvent(scrollable)
            }

            MotionEvent.ACTION_MOVE -> {
                val delta = ev.y - lastTouchY // <0 手指上滑（看下方内容）
                lastTouchY = ev.y
                val handled = super.onTouchEvent(ev)
                // 方向感知：仅当前拖动方向上内部还有内容可滚时才继续锁定；
                // 到边界（如顶部继续下拉回看列表）→ 放行，剩余手势由外层 RecyclerView 接管
                val canInner = if (delta < 0) canScrollVertically(1) else canScrollVertically(-1)
                parent?.requestDisallowInterceptTouchEvent(canInner)
                return handled
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.onTouchEvent(ev)
    }

    init {
        // 读取自定义属性（可选）
        attrs?.let {
            val typedArray = context.obtainStyledAttributes(it, R.styleable.MaxHeightNestedScrollView)
            maxHeight = typedArray.getDimensionPixelSize(
                R.styleable.MaxHeightNestedScrollView_maxHeight,
                -1
            )
            typedArray.recycle()
        }
        // 嵌套滚动支持（NestedScrollView 默认即开启，显式声明意图与 XML 语义一致）
        isNestedScrollingEnabled = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var targetHeightSpec = heightMeasureSpec
        if (maxHeight > 0) {
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val size = MeasureSpec.getSize(heightMeasureSpec)

            // 当内容测算高度大于最大限制时，强制截断为 maxHeight
            val adjustedHeight = if (mode == MeasureSpec.UNSPECIFIED) {
                maxHeight
            } else {
                minOf(size, maxHeight)
            }
            targetHeightSpec = MeasureSpec.makeMeasureSpec(adjustedHeight, MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthMeasureSpec, targetHeightSpec)
    }
}