package io.noties.markwon.block.view;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.AttributeSet;
import android.util.TypedValue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.noties.markwon.block.render.MarkdownConfig;
import io.noties.markwon.block.render.MarkdownContent;
import io.noties.markwon.block.render.MdTheme;
import io.noties.markwon.block.render.StreamSpans;
import android.widget.TextView;
import io.noties.markwon.ext.tables.TableRowSpan;
import io.noties.markwon.ext.tables.TableScrollTouchListener;
import io.noties.markwon.image.AsyncDrawableScheduler;

/**
 * 文本块视图（复刻豆包 CustomMarkdownTextView）。
 *
 * <p>职责：
 * <ul>
 *     <li>接收 {@link MarkdownContent} 直接 setText（SPANNABLE）；</li>
 *     <li>表格 invalidator 接线：markwon 手动 {@code setText} 生命周期下
 *         {@link TableRowSpan} 的行高不会自收敛（invalidator 为 null），
 *         必须手动挂「合并 invalidate → 主线程 setText」触发重测重排；</li>
 *     <li>流式打字机：新后缀 {@link StreamSpans.ForegroundAlphaSpan} 淡入
 *     （取消末尾闪烁光标，避免干扰内容阅读）；</li>
 *     <li>hasTable 时 onMeasure 走 EXACTLY 宽度，保证表格行盒取满列宽。</li>
 * </ul>
 */
public class CustomMarkdownTextView extends TextView implements ViewRecycler {

    private static final int FADE_STEPS = 24;
    private static final long FADE_STEP_MS = 16L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final MdTheme theme;

    private int markdownWidth;
    private boolean hasTable;

    // 淡入动画状态
    private StreamSpans.ForegroundAlphaSpan fadeSpan;
    private int fadeStep;
    private final Runnable fadeTick = new Runnable() {
        @Override
        public void run() {
            if (fadeSpan == null) {
                return;
            }
            fadeStep++;
            final int alpha = Math.min(255, Math.round(255f * fadeStep / FADE_STEPS));
            fadeSpan.setAlpha(alpha);
            invalidate();
            if (fadeStep < FADE_STEPS) {
                mainHandler.postDelayed(this, FADE_STEP_MS);
            } else {
                fadeSpan = null;
            }
        }
    };

    public CustomMarkdownTextView(@NonNull Context context) {
        this(context, MdTheme.light(), new MarkdownConfig.Builder().build());
    }

    public CustomMarkdownTextView(
            @NonNull Context context,
            @NonNull MdTheme theme,
            @NonNull MarkdownConfig config) {
        super(context);
        this.theme = theme;
        initTextView();
    }

    private void initTextView() {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        setTextColor(theme.getTextColor());
        setBackgroundColor(theme.getTextBackground());
        setMovementMethod(LinkMovementMethod.getInstance());
        setIncludeFontPadding(false);
        // 行距微扩（+2dp / 1.12 倍），正文多行时更透气
        final float density = getResources().getDisplayMetrics().density;
        setLineSpacing(Math.round(2f * density), 1.12f);
    }

    /**
     * 绑定渲染内容。
     *
     * @param content       渲染结果（富文本 + 能力探测）
     * @param markdownWidthPx 可用宽度（表格 EXACTLY 用；&lt;=0 时回退默认测量）
     * @param streaming     打字机态：追加闪烁光标
     * @param fadeFrom      淡入起点（该段为新增后缀，alpha 0→255）；&lt;=0 不淡入
     */
    public void setMarkdownContent(
            @NonNull MarkdownContent content,
            int markdownWidthPx,
            boolean streaming,
            int fadeFrom) {
        stopEffects();
        markdownWidth = markdownWidthPx;
        hasTable = content.hasTable();

        final SpannableStringBuilder ssb = new SpannableStringBuilder(content.getSpannable());

        // 表格 invalidator 接线（终态渲染必须：行高收敛；流式打字机每帧全量 setText
        // 本就驱动重测，再加 invalidator 的 post setText 会造成表格反复闪烁）
        if (hasTable && !streaming) {
            wireTableRowInvalidator(ssb);
        }

        // 新增后缀淡入（仅主题允许时）
        if (theme.isAlphaFade() && fadeFrom > 0 && fadeFrom < ssb.length()) {
            fadeSpan = new StreamSpans.ForegroundAlphaSpan(0);
            fadeStep = 0;
            ssb.setSpan(fadeSpan, fadeFrom, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        setText(ssb, TextView.BufferType.SPANNABLE);

        // 图片加载接线：AsyncDrawableScheduler 扫描文本中的 AsyncDrawableSpan 并 attach
        // （attach 即触发 loader.load），加载完成后 invalidator 重新 setText 收敛行高
        AsyncDrawableScheduler.schedule(this);

        if (hasTable) {
            // 直接 setText 绕过 Markwon.setText → TablePlugin.afterSetText 不触发，
            // 必须手动挂载表格横向滚动触控（幂等：GestureRouter 按 key 覆盖）
            TableScrollTouchListener.attach(this);
        }

        if (fadeSpan != null) {
            mainHandler.post(fadeTick);
        }
    }

    /**
     * markwon 表格接线：给每个 {@link TableRowSpan} 挂 invalidator。
     * 合并多次 invalidate，最后只 post 一次 setText 触发重测/重排，行高收敛到内容高度。
     */
    private void wireTableRowInvalidator(@NonNull SpannableStringBuilder ssb) {
        final TableRowSpan[] rows = ssb.getSpans(0, ssb.length(), TableRowSpan.class);
        if (rows.length == 0) {
            hasTable = false;
            return;
        }
        final Runnable reflow = new Runnable() {
            @Override
            public void run() {
                if (!TextUtils.isEmpty(getText())) {
                    setText(getText()); // 触发重测重排
                }
            }
        };
        for (final TableRowSpan row : rows) {
            row.invalidator(new TableRowSpan.Invalidator() {
                @Override
                public void invalidate() {
                    mainHandler.removeCallbacks(reflow);
                    mainHandler.post(reflow);
                }
            });
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // 表格按给定宽度精确取宽，保证列均分不溢出
        if (hasTable && markdownWidth > 0) {
            final int exactW = MeasureSpec.makeMeasureSpec(markdownWidth, MeasureSpec.EXACTLY);
            super.onMeasure(exactW, heightMeasureSpec);
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    @Override
    public void onViewRecycled() {
        stopEffects();
        // 解除图片 attach（触发 loader.cancel），避免复用视图携带旧请求
        AsyncDrawableScheduler.unschedule(this);
        setText("");
        markdownWidth = 0;
        hasTable = false;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopEffects();
        // 兜底：attach 监听器已处理，这里再显式解除（RecyclerView 复用前 detach 场景）
        AsyncDrawableScheduler.unschedule(this);
    }

    private void stopEffects() {
        mainHandler.removeCallbacks(fadeTick);
        fadeSpan = null;
    }
}