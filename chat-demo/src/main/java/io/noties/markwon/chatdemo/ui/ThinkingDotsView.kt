package io.noties.markwon.chatdemo.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * 「思考中」三点圈动动画（移植自 doslas ai/view/ThinkingDotsView.kt）
 */
class ThinkingDotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val dotRadius = 5f * resources.displayMetrics.density
    private val dotSpacing = 8f * resources.displayMetrics.density

    private val colors = intArrayOf(
        Color.parseColor("#D9D9D9"),
        Color.parseColor("#9A9A9A"),
        Color.parseColor("#5A5A5A")
    )

    private var offset = 0

    private val animator = ValueAnimator.ofInt(0, 2).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            offset = it.animatedValue as Int
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = (dotRadius * 2 * 3 + dotSpacing * 2).toInt()
        val height = (dotRadius * 2).toInt()
        setMeasuredDimension(width + paddingLeft + paddingRight, height + paddingTop + paddingBottom)
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        for (i in 0 until 3) {
            val cx = paddingLeft.toFloat() + dotRadius + i * (dotRadius * 2 + dotSpacing)
            paint.color = colors[(i - offset + 3) % 3]
            canvas.drawCircle(cx, cy, dotRadius, paint)
        }
    }
}