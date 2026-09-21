package io.noties.markwon.block.render;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.text.style.ReplacementSpan;
import android.text.style.UpdateAppearance;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 流式渲染辅助 Span（复刻豆包 StreamSpans）。
 *
 * <ul>
 *     <li>{@link ForegroundAlphaSpan}：流式新增段淡入（alpha 0 → 255，由宿主逐帧驱动）；</li>
 *     <li>{@link TypingCursorSpan}：文本末尾的打字光标（\uFFFC 占位 + 竖条闪烁）；</li>
 * </ul>
 */
public final class StreamSpans {

    /** 打字机光标占位字符 */
    public static final char CURSOR_PLACEHOLDER = '\uFFFC';

    private StreamSpans() {
    }

    /**
     * 淡入 Span：修改 {@link android.text.TextPaint#setAlpha(int)} 让一段文字整体透明渐变。
     * 宿主拿到实例后逐帧 {@link #setAlpha(int)} + {@code invalidate()} 即可实现打字机淡入。
     */
    public static final class ForegroundAlphaSpan extends CharacterStyle implements UpdateAppearance {

        private int alpha;

        public ForegroundAlphaSpan(int alpha) {
            this.alpha = alpha;
        }

        public int getAlpha() {
            return alpha;
        }

        public void setAlpha(int alpha) {
            this.alpha = alpha;
        }

        @Override
        public void updateDrawState(@NonNull TextPaint ds) {
            ds.setAlpha(alpha);
        }
    }

    /**
     * 打字机光标：一个 {@link ReplacementSpan}，在文字末尾绘制竖条。
     * 闪烁由宿主定时 {@link #setVisible(boolean)} + {@code invalidate()} 驱动。
     */
    public static final class TypingCursorSpan extends ReplacementSpan {

        private final int color;
        private final float widthPx;
        private boolean visible = true;

        public TypingCursorSpan(@ColorInt int color, float widthPx) {
            this.color = color;
            this.widthPx = widthPx;
        }

        public boolean isVisible() {
            return visible;
        }

        public void setVisible(boolean visible) {
            this.visible = visible;
        }

        @Override
        public int getSize(@NonNull Paint paint, CharSequence text, int start, int end,
                           @Nullable Paint.FontMetricsInt fm) {
            // 只要极窄宽度即可；行高无需特殊处理，TextPaint 会按当前字号给出
            return Math.max(1, (int) (widthPx + 2.0f));
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end,
                         float x, int top, int y, int bottom, @NonNull Paint paint) {
            if (!visible) {
                return;
            }
            final Paint old = paint;
            final int oldColor = old.getColor();
            old.setColor(color);
            // 画一条竖线（左右各留 1px 呼吸空间）
            final float left = x + 1.0f;
            final float right = x + widthPx + 1.0f;
            canvas.drawRect(new Rect((int) left, top, (int) right, bottom), old);
            old.setColor(oldColor);
        }
    }

    /**
     * 判断文本末尾是否是打字光标占位（流式追加时避免重复叠加光标）。
     */
    public static boolean hasTrailingCursor(@NonNull CharSequence text) {
        return text.length() > 0 && text.charAt(text.length() - 1) == CURSOR_PLACEHOLDER;
    }
}