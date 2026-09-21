package io.noties.markwon.block.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;

import io.noties.markwon.block.model.Block;
import io.noties.markwon.block.render.MarkdownBlockAssembler;
import io.noties.markwon.block.render.MarkdownConfig;
import io.noties.markwon.block.render.MarkwonFactory;
import io.noties.markwon.block.render.MdTheme;
import io.noties.markwon.block.render.OnLinkClick;

/**
 * Markdown 文本块视图（一键接入入口，复刻豆包 MarkdownTextBlockView）。
 *
 * <p>用法（两种）：
 * <ol>
 *     <li><b>整段渲染</b>：布局里放一个 {@code MarkdownTextBlockView}，
 *         调用 {@link #renderMarkdown(String)} 一次出全量富文本；</li>
 *     <li><b>流式增量</b>：SSE/token 到达时逐 chunk 调用
 *         {@link #appendMarkdown(String)}，内部整体重 parse +
 *         视图树复用 + 新后缀淡入 + 打字光标，实现打字机效果。</li>
 * </ol>
 *
 * <p>解析与渲染均可插拔：
 * <ul>
 *     <li>{@link MarkwonFactory} → 自定义 Markwon 解析器（插件集）；</li>
 *     <li>{@link BlockViewFactory} → 自定义块渲染视图分发。</li>
 * </ul>
 * 全局默认通过 setDefaultXxx 静态方法配置；实例级可用同名 setter 覆盖。
 */
public class MarkdownTextBlockView extends AbsBlockView {

    // ---------------------------------------------------------------------
    // 全局默认（静态）
    // ---------------------------------------------------------------------

    @Nullable
    private static MarkwonFactory sMarkwonFactory;
    @Nullable
    private static BlockViewFactory sBlockViewFactory;
    @Nullable
    private static MdTheme sTheme;
    @Nullable
    private static MarkdownConfig sConfig;

    public static void setDefaultMarkwonFactory(@Nullable MarkwonFactory factory) {
        sMarkwonFactory = factory;
    }

    public static void setDefaultBlockViewFactory(@Nullable BlockViewFactory factory) {
        sBlockViewFactory = factory;
    }

    public static void setDefaultTheme(@NonNull MdTheme theme) {
        sTheme = theme;
    }

    public static void setDefaultConfig(@NonNull MarkdownConfig config) {
        sConfig = config;
    }

    // ---------------------------------------------------------------------
    // 实例
    // ---------------------------------------------------------------------

    private final MarkdownBlockAssembler assembler = new MarkdownBlockAssembler();
    private final String viewKey;

    private MarkwonFactory markwonFactory = sMarkwonFactory;
    private BlockViewFactory blockViewFactory = sBlockViewFactory;
    private MdTheme theme = sTheme != null ? sTheme : MdTheme.light();
    private MarkdownConfig config = sConfig != null ? sConfig : new MarkdownConfig.Builder().build();
    @Nullable
    private OnLinkClick onLinkClick;

    /** 增量追加的累计文本（renderMarkdown 时整体替换） */
    private String accumulated = "";

    public MarkdownTextBlockView(@NonNull Context context) {
        this(context, null);
    }

    public MarkdownTextBlockView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MarkdownTextBlockView(
            @NonNull Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        viewKey = "mtbv-" + Integer.toHexString(System.identityHashCode(this));
    }

    @Override
    protected void initView(@NonNull Context context, @Nullable AttributeSet attrs) {
        // 容器仅需纵向排列，基类已 setOrientation(VERTICAL)
    }

    // ---------------------------------------------------------------------
    // 一键接入 API
    // ---------------------------------------------------------------------

    /**
     * 整段渲染（非流式）。旧内容会被整体替换。
     */
    public void renderMarkdown(@NonNull String markdown) {
        renderMarkdown(markdown, null);
    }

    public void renderMarkdown(@NonNull String markdown, @Nullable OnLinkClick onClick) {
        onLinkClick = onClick;
        accumulated = markdown == null ? "" : markdown;
        internal(false);
    }

    /**
     * 增量追加（流式打字机）。自动累计到已有文本之后。
     */
    public void appendMarkdown(@NonNull String chunk) {
        appendMarkdown(chunk, null, true);
    }

    public void appendMarkdown(@NonNull String chunk, @Nullable OnLinkClick onClick, boolean streaming) {
        if (chunk == null) {
            return;
        }
        onLinkClick = onClick;
        accumulated += chunk;
        internal(streaming);
    }

    // ---------------------------------------------------------------------
    // 实例配置（覆盖全局默认）
    // ---------------------------------------------------------------------

    public void setTheme(@NonNull MdTheme theme) {
        this.theme = theme;
    }

    public void setConfig(@NonNull MarkdownConfig config) {
        this.config = config;
    }

    public void setMarkwonFactory(@Nullable MarkwonFactory factory) {
        this.markwonFactory = factory;
    }

    public void setBlockViewFactory(@Nullable BlockViewFactory factory) {
        this.blockViewFactory = factory;
    }

    public void setOnLinkClick(@Nullable OnLinkClick onLinkClick) {
        this.onLinkClick = onLinkClick;
    }

    /** 整段 markdown 的当前累计文本 */
    @NonNull
    public String getMarkdownText() {
        return accumulated;
    }

    // ---------------------------------------------------------------------
    // 基类接入（RecyclerView 场景：作为块的渲染视图）
    // ---------------------------------------------------------------------

    @Override
    public void bindData(@NonNull Block block, @Nullable Map<String, Object> payload) {
        if (block instanceof Block.TextBlock) {
            renderMarkdown(((Block.TextBlock) block).getText(), onLinkClick);
        }
    }

    @Override
    public void onViewRecycled() {
        for (int i = getChildCount() - 1; i >= 0; i--) {
            final View v = getChildAt(i);
            if (v instanceof ViewRecycler) {
                ((ViewRecycler) v).onViewRecycled();
            }
            removeViewAt(i);
        }
        accumulated = "";
    }

    // ---------------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------------

    private void internal(boolean streaming) {
        assembler.assemble(
                getContext(),
                this,
                viewKey,
                accumulated,
                theme,
                config,
                markwonFactory,
                blockViewFactory,
                onLinkClick,
                availableWidthPx(),
                streaming);
    }

    /** 容器当前可用宽度；未布局时返回 0（表格 EXACTLY 会回退默认测量） */
    private int availableWidthPx() {
        final int w = getWidth();
        if (w > 0) {
            return Math.max(0, w - getPaddingLeft() - getPaddingRight());
        }
        return 0;
    }
}