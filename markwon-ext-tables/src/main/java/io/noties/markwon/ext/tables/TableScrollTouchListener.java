package io.noties.markwon.ext.tables;

import android.text.Layout;
import android.text.Spannable;
import android.text.Spanned;
import android.text.method.MovementMethod;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.noties.markwon.core.scroll.GestureRouter;

/**
 * Turns a horizontal drag <b>anywhere over a table</b> — cells, borders, the empty space to the
 * right of a short row, the scrollbar itself — into a scroll of that table.
 *
 * <p>Deliberately installed as a {@code View.OnTouchListener} (through
 * {@link GestureRouter}) rather than as a {@link MovementMethod}: a
 * {@code MovementMethod} is only ever reached from {@code TextView#onTouchEvent}, which a plain
 * markdown {@code TextView} (not clickable, not selectable) does not consume — the gesture would
 * end at {@code ACTION_DOWN} and no {@code MOVE} event would ever arrive. An
 * {@code OnTouchListener} is asked <em>before</em> the view's own handling, so the drag works
 * regardless of how the host configured its {@code TextView}.
 *
 * <h3>Hit-testing the whole block, not the glyphs</h3>
 * The table is located by its <b>vertical span</b> ({@link Layout#getLineTop(int)} /
 * {@link Layout#getLineBottom(int)} of the lines the {@link TableSpan} covers) instead of by
 * resolving the character under the finger. Only the rectangle test covers what the user
 * perceives as "the table": the padding around the cells, the scrollbar band, and the part of
 * every row that a horizontally scrolled table has already pushed out of view.
 *
 * @since 4.6.3
 */
public final class TableScrollTouchListener implements View.OnTouchListener {

    /**
     * Registers this listener on {@code textView}. Safe to call for every {@code setText} —
     * the router replaces its own entry, and the listener is stateless between gestures.
     *
     * <p>公开为 {@code public static}：markwon-block 的流式/增量管线直接
     * {@code setText} 绕过 {@code Markwon.setText}（{@code TablePlugin.afterSetText}
     * 不会触发），需要手动调用本方法完成横向滚动触控挂载。
     */
    public static void attach(@NonNull TextView textView) {
        GestureRouter.attach(textView)
                .add(TableScrollTouchListener.class, new TableScrollTouchListener(textView));
    }

    private final TextView textView;
    private final int touchSlop;

    /**
     * Table the gesture started on. {@code null} = this listener does not own the gesture
     * (nothing scrollable was hit, or a vertical drag has been handed back to the parent).
     */
    @Nullable
    private TableSpan target;

    /**
     * The {@code ACTION_DOWN} of the gesture in flight, kept only so that a <em>tap</em> can be
     * replayed into the {@code TextView}'s movement method — see {@link #replay(MotionEvent,
     * MotionEvent)}. Recycled as soon as the gesture ends.
     */
    @Nullable
    private MotionEvent down;

    private float lastX;
    private float lastY;
    private boolean dragging;

    private TableScrollTouchListener(@NonNull TextView textView) {
        this.textView = textView;
        this.touchSlop = ViewConfiguration.get(textView.getContext()).getScaledTouchSlop();
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {

            case MotionEvent.ACTION_DOWN: {
                final TableSpan span = findTable(event);
                if (span == null || !span.canScroll()) {
                    // nothing to drag: leave the gesture to the view (link taps, selection) and
                    // to the parent ScrollView, exactly as if this listener were not here
                    reset();
                    return false;
                }
                target = span;
                lastX = event.getX();
                lastY = event.getY();
                dragging = false;
                down = MotionEvent.obtain(event);
                // consume right away, otherwise a plain TextView would not report any of the
                // MOVE events to us
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                final TableSpan target = this.target;
                if (target == null) {
                    return false;
                }
                final float dx = event.getX() - lastX;
                final float dy = event.getY() - lastY;
                if (!dragging) {
                    if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > touchSlop) {
                        dragging = true;
                    } else if (Math.abs(dy) > touchSlop) {
                        // vertical gesture: hand it back to the parent (ScrollView)
                        reset();
                        return false;
                    } else {
                        return true;
                    }
                }
                final ViewParent parent = v.getParent();
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(true);
                }
                if (target.scrollBy((int) -dx)) {
                    lastX = event.getX();
                    lastY = event.getY();
                    textView.invalidate();
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (target == null) {
                    return false;
                }
                final boolean wasDragging = dragging;
                final boolean released = event.getActionMasked() == MotionEvent.ACTION_UP;
                final MotionEvent down = this.down;
                reset();

                if (!wasDragging && released) {
                    replay(down, event);
                }
                if (down != null) {
                    down.recycle();
                }

                final ViewParent parent = v.getParent();
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(false);
                }
                return true;
            }

            default:
                return false;
        }
    }

    private void reset() {
        target = null;
        down = null;
        dragging = false;
    }

    /**
     * Table under the touch, or {@code null} when the finger is not over one.
     *
     * <p>A rectangle test over the block's lines — see the class comment for why the character
     * under the finger is not the right question to ask.
     */
    @Nullable
    private TableSpan findTable(@NonNull MotionEvent event) {
        final Layout layout = textView.getLayout();
        final CharSequence text = textView.getText();
        if (layout == null || layout.getLineCount() == 0 || !(text instanceof Spanned)) {
            return null;
        }

        final Spanned spanned = (Spanned) text;
        final TableSpan[] spans = spanned.getSpans(0, spanned.length(), TableSpan.class);
        if (spans == null) {
            return null;
        }

        final float y = event.getY() + textView.getScrollY() - textView.getCompoundPaddingTop();
        for (TableSpan span : spans) {
            final int start = spanned.getSpanStart(span);
            final int end = Math.min(spanned.getSpanEnd(span), text.length());
            if (start < 0 || start >= end) {
                continue;
            }
            final int firstLine = lineForOffset(layout, start);
            final int lastLine = lineForOffset(layout, end - 1);
            if (y >= layout.getLineTop(firstLine) && y <= layout.getLineBottom(lastLine)) {
                return span;
            }
        }

        return null;
    }

    /**
     * Hands a <b>tap</b> — a gesture that never became a drag — back to the {@code TextView}'s
     * movement method.
     *
     * <p>Needed because consuming the {@code ACTION_DOWN} (which the drag requires, see the
     * class comment) also hides the gesture from {@code TextView#onTouchEvent}, and with it from
     * {@link TableAwareMovementMethod}, whose whole job is to open the in-cell links. The
     * {@code DOWN} is replayed before the {@code UP} because that method derives the click
     * position from the pair.
     *
     * <p>Without a movement method there is nothing to replay to, and nothing was clickable to
     * begin with — the tap simply does nothing, which is what the user sees today.
     */
    private void replay(@Nullable MotionEvent down, @NonNull MotionEvent up) {
        if (down == null) {
            return;
        }
        final MovementMethod movementMethod = textView.getMovementMethod();
        final CharSequence text = textView.getText();
        if (movementMethod == null || !(text instanceof Spannable)) {
            return;
        }
        final Spannable spannable = (Spannable) text;
        movementMethod.onTouchEvent(textView, spannable, down);
        movementMethod.onTouchEvent(textView, spannable, up);
    }

    private static int lineForOffset(@NonNull Layout layout, int offset) {
        return Math.min(
                Math.max(layout.getLineForOffset(offset), 0),
                layout.getLineCount() - 1);
    }
}
