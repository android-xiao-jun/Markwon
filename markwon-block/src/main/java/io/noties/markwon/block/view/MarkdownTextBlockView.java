package io.noties.markwon.block.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;
import java.util.regex.Pattern;

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

    /** 已到达的全部文本（渲染目标；appendMarkdown 累计、renderMarkdown 整体替换） */
    private String accumulated = "";
    /** 已渲染长度（打字机缓冲推进游标） */
    private int renderedLength = 0;
    /** 流结束兜底重绘（renderMarkdown 同文本）到达但缓冲未追平：追平后做一次无光标终态渲染 */
    private boolean finishPending = false;
    /** 打字机 tick 是否已排队 */
    private boolean typingTickPosted = false;

    /** 打字机推进节奏：每帧一步，步长按积压量自适应（约 10 步追平，最少 2 字） */
    private static final long TYPE_STEP_MS = 16L;

    private final Runnable typingTick = new Runnable() {
        @Override
        public void run() {
            typingTickPosted = false;
            if (renderedLength >= accumulated.length()) {
                finishTypingIfNeeded();
                return;
            }
            final int backlog = accumulated.length() - renderedLength;
            final int step = Math.max(2, (backlog + 9) / 10);
            renderedLength = Math.min(accumulated.length(), renderedLength + step);
            internalRenderText(accumulated.substring(0, renderedLength), true);
            if (renderedLength < accumulated.length()) {
                typingTickPosted = true;
                postDelayed(typingTick, TYPE_STEP_MS);
            } else {
                finishTypingIfNeeded();
            }
        }
    };

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
     *
     * <p>流结束兜底重绘保护：若传入文本与当前缓冲目标一致且打字机尚未追平
     * （流式结束后上游可能触发整条重绘），不打断打字机——标记
     * {@code finishPending}，缓冲追平后自动做一次无光标的终态渲染。
     */
    public void renderMarkdown(@NonNull String markdown) {
        renderMarkdown(markdown, null);
    }

    public void renderMarkdown(@NonNull String markdown, @Nullable OnLinkClick onClick) {
        onLinkClick = onClick;
        final String text = markdown == null ? "" : markdown;
        if (text.equals(accumulated) && renderedLength < accumulated.length()) {
            // 同一内容的兜底重绘（如流结束 notifyItemChanged）：等打字机自然追平
            finishPending = true;
            return;
        }
        cancelTyping();
        accumulated = text;
        renderedLength = text.length();
        internalRenderText(text, false);
    }

    /**
     * 增量追加（流式打字机）。自动累计到已有文本之后。
     *
     * <p>chunk 不直接整体渲染，而是进入打字机缓冲：按 16ms/步自适应步长
     * 逐步推进渲染游标，无论 chunk 到达多快多大，视觉上都是平滑打字机。
     */
    public void appendMarkdown(@NonNull String chunk) {
        appendMarkdown(chunk, null, true);
    }

    public void appendMarkdown(@NonNull String chunk, @Nullable OnLinkClick onClick, boolean streaming) {
        if (chunk == null) {
            return;
        }
        onLinkClick = onClick;
        if (!streaming || chunk.isEmpty()) {
            // 非流式语义：直接渲染到最新态
            accumulated += chunk;
            renderedLength = accumulated.length();
            cancelTyping();
            internalRenderText(accumulated, false);
            return;
        }
        accumulated += chunk;
        if (!typingTickPosted) {
            typingTickPosted = true;
            post(typingTick);
        }
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
        cancelTyping();
        for (int i = getChildCount() - 1; i >= 0; i--) {
            final View v = getChildAt(i);
            if (v instanceof ViewRecycler) {
                ((ViewRecycler) v).onViewRecycled();
            }
            removeViewAt(i);
        }
        accumulated = "";
        renderedLength = 0;
    }

    // ---------------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------------

    /** 渲染指定文本（打字机 tick 喂前缀子串 / 终态喂全量），streaming 控制光标 */
    private void internalRenderText(@NonNull String text, boolean streaming) {
        if (streaming) {
            // 流式中间态：commonmark 需「表头行 + 分隔行」齐全才生成 TableBlock，
            // 打字期间表格一直以纯文本呈现、header 不可见 → 先补齐在途表格语法
            text = completeStreamingTableTail(text);
        }
        assembler.assemble(
                getContext(),
                this,
                viewKey,
                text,
                theme,
                config,
                markwonFactory,
                blockViewFactory,
                onLinkClick,
                availableWidthPx(),
                streaming);
    }

    /** 打字机追平后的收尾：若流结束兜底重绘已到达，做一次无光标终态渲染 */
    private void finishTypingIfNeeded() {
        if (finishPending) {
            finishPending = false;
            internalRenderText(accumulated, false);
        }
    }

    /** 取消打字机推进（全量替换 / 回收场景） */
    private void cancelTyping() {
        removeCallbacks(typingTick);
        typingTickPosted = false;
        finishPending = false;
    }

    /** 容器当前可用宽度；未布局时返回 0（表格 EXACTLY 会回退默认测量） */
    private int availableWidthPx() {
        final int w = getWidth();
        if (w > 0) {
            return Math.max(0, w - getPaddingLeft() - getPaddingRight());
        }
        return 0;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // RecyclerView 滚动回收后 reattach：恢复未完成的打字机推进
        if (!typingTickPosted && renderedLength < accumulated.length()) {
            typingTickPosted = true;
            post(typingTick);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // 暂停推进（保留进度与目标，reattach 后继续）
        removeCallbacks(typingTick);
        typingTickPosted = false;
    }

    // ---------------------------------------------------------------------
    // 流式在途表格补齐（streaming tick 专用；终态渲染一律不进入此路径）
    // ---------------------------------------------------------------------

    /** 分隔单元格正则：--- / :--- / :---: 等 */
    private static final Pattern DELIMITER_CELL = Pattern.compile("^\\s*:?-+:?\\s*$");

    /**
     * 流式渲染前补齐尾部「表格语法在途」：commonmark 必须等表头行 + 分隔行
     * 都完整到达才生成 TableBlock。SSE 打字期间分隔行未到达 / 正在输入时，
     * 表格一直以纯文本呈现（管道符割裂、header 不可见），只有完整加载才变成表格。
     *
     * <p>这里按表头列数合成 / 补齐分隔行，让 commonmark 立即生成表格：
     * <ul>
     *     <li>最后一行是分隔行（如 {@code | ---}）→ 补足列数、闭合尾部 {@code |}；</li>
     *     <li>最后一行是内容行（表头或表体前的表头行）且前文无分隔行 → 追加一行合成分隔行。</li>
     * </ul>
     *
     * @return 需要补齐时返回修改后的文本；否则返回原对象（零拷贝）。
     */
    @NonNull
    private static String completeStreamingTableTail(@NonNull String text) {
        if (text.isEmpty()) {
            return text;
        }
        final String[] lines = text.split("\\r?\\n", -1);
        // 最后一个非空行
        int last = lines.length - 1;
        while (last > 0 && lines[last].trim().isEmpty()) {
            last--;
        }
        final String lastLine = lines[last];
        final String lastTrim = lastLine.trim();
        if (lastTrim.isEmpty() || lastTrim.charAt(0) != '|') {
            return text;
        }

        // 表头行（前一个非空行；表格是消息开头时为 null → 以自身为表头）
        String headerRow = null;
        for (int i = last - 1; i >= 0; i--) {
            if (!lines[i].trim().isEmpty()) {
                headerRow = lines[i];
                break;
            }
        }

        // 分隔行在途 / 列数不足：补齐到与表头一致
        if (isDelimiterRow(lastLine)) {
            final int cols = Math.max(2, countCells(headerRow == null ? lastLine : headerRow));
            final String padded = padDelimiterRow(lastLine, cols);
            if (padded.equals(lastLine)) {
                return text;
            }
            lines[last] = padded;
            return join(lines);
        }

        // 内容行分支：前文已有分隔行 → 表格已成立，当前是表体行，无需合成
        if (headerRow != null && hasDelimiterRowBefore(lines, last)) {
            return text;
        }
        // 表头未闭合（缺尾 '|'）或列数不足 → 等待其输入完成
        if (!lastTrim.endsWith("|") || countCells(lastLine) < 2) {
            return text;
        }
        // 表头行已就位但分隔行未开始 → 立即合成同列数分隔行，header 流式可见
        final int cells = countCells(lastLine);
        lines[last] = lastLine + '\n' + delimiterRow(cells);
        return join(lines);
    }

    /** {@code |} 之间非空段的数量（即表格列数） */
    private static int countCells(@NonNull String line) {
        final String[] parts = line.split("\\|", -1);
        int cells = 0;
        for (String part : parts) {
            if (!part.trim().isEmpty()) {
                cells++;
            }
        }
        return cells;
    }

    /** 行内非空段是否全部为分隔符（--- / :--- / :---: 等） */
    private static boolean isDelimiterRow(@NonNull String line) {
        final String[] parts = line.split("\\|", -1);
        for (String part : parts) {
            if (part.trim().isEmpty()) {
                continue;
            }
            if (!DELIMITER_CELL.matcher(part.trim()).matches()) {
                return false;
            }
        }
        return true;
    }

    /** 最后一行之前（连续管道行范围内）是否已有完整分隔行 → 表格已成立 */
    private static boolean hasDelimiterRowBefore(@NonNull String[] lines, int last) {
        for (int i = last - 1; i >= 0; i--) {
            final String l = lines[i];
            if (l.trim().isEmpty()) {
                continue;
            }
            if (l.trim().charAt(0) != '|') {
                break; // 管道行中断 → 之前的行不属于同一表格
            }
            if (isDelimiterRow(l) && l.trim().endsWith("|")) {
                return true;
            }
        }
        return false;
    }

    /** 分隔行补齐列数：闭合尾部 {@code |} 后按需追加 {@code | --- |} */
    @NonNull
    private static String padDelimiterRow(@NonNull String line, int cols) {
        String trim = line.trim();
        final StringBuilder sb = new StringBuilder(trim);
        if (!trim.endsWith("|")) {
            sb.append('|');
        }
        final int cells = countCells(sb.toString());
        for (int i = cells; i < cols; i++) {
            sb.append(" --- ").append('|');
        }
        return sb.toString();
    }

    /** 生成完整的 n 列分隔行 */
    @NonNull
    private static String delimiterRow(int cols) {
        final StringBuilder sb = new StringBuilder("|");
        for (int i = 0; i < cols; i++) {
            sb.append(" --- |");
        }
        return sb.toString();
    }

    @NonNull
    private static String join(@NonNull String[] lines) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }
}