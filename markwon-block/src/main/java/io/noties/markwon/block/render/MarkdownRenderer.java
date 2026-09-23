package io.noties.markwon.block.render;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.text.style.LineHeightSpan;
import android.text.style.ReplacementSpan;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.ListBlock;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.StrongEmphasis;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.MarkwonVisitor;
import io.noties.markwon.block.model.BlockContentExtractor;
import io.noties.markwon.block.view.ImageBlockView;
import io.noties.markwon.block.view.LinkHandler;
import io.noties.markwon.core.CorePlugin;
import io.noties.markwon.core.CoreProps;
import io.noties.markwon.core.MarkwonTheme;
import io.noties.markwon.core.spans.LastLineSpacingSpan;
import io.noties.markwon.core.spans.LinkSpan;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.ext.tables.TableTheme;
import io.noties.markwon.image.AsyncDrawable;
import io.noties.markwon.image.AsyncDrawableLoader;
import io.noties.markwon.image.AsyncDrawableSpan;
import io.noties.markwon.image.ImageSizeResolverDef;

/**
 * Markdown 渲染器（复刻豆包 MarkwonRender + MarkwonProvider）。
 *
 * <p>渲染管线：
 * <ol>
 *     <li>用同一个 {@link Markwon} 实例 {@link Markwon#parse(String)} 原始文本，
 *         走 AST 做能力探测（链接 / 表格 / 粗体 / 图片）；</li>
 *     <li>图片预处理：行内 {@code ![](url)} → □ 占位符（本库不发起真实网络加载）；</li>
 *     <li>{@link Markwon#toMarkdown(String)} 渲染富文本；</li>
 *     <li>{@link LinkSpan} → {@link UrlClickableSpan}（点击优先回调外部，否则系统浏览器/复制）；</li>
 *     <li>□ 占位符 → {@link ImagePlaceholderSpan} 占位图块。</li>
 * </ol>
 *
 * <p>{@link Markwon} 实例按（主题 hash, 配置 hash, 工厂实例）缓存复用，
 * 避免每次渲染重建解析器。
 */
public final class MarkdownRenderer {

    /** markwon 实例缓存：key = theme hash + config hash + factory 实例标识 */
    private static final ConcurrentHashMap<MarkwonKey, Markwon> MARKWON_CACHE = new ConcurrentHashMap<>();

    /** 行内图片占位符：渲染前替换 ![](url)，渲染后回填占位 Span */
    private static final char IMAGE_PLACEHOLDER = '\u25A1';

    private MarkdownRenderer() {
    }

    /**
     * 渲染一段 markdown 文本。
     *
     * @param textRaw      原始 markdown 文本（文本块内容）
     * @param onLinkClick  链接点击回调，可为 null（默认系统浏览器 / 复制兜底）
     * @return 渲染结果（富文本 + 能力探测）
     */
    @NonNull
    public static MarkdownContent render(
            @NonNull Context context,
            @NonNull String textRaw,
            @NonNull MdTheme theme,
            @NonNull MarkdownConfig config,
            @Nullable MarkwonFactory factory,
            @Nullable OnLinkClick onLinkClick) {
        final Markwon markwon = markwon(context, theme, config, factory);

        // 1) 能力探测（原始文本 AST）
        final Node root = markwon.parse(textRaw);
        final Capabilities cap = new Capabilities();
        walkCapabilities(root, cap);

        // 2) 图片预处理：![](...) → □ 占位
        final List<ImageMark> images = new ArrayList<>();
        final String processed = preProcessImages(textRaw, images);

        // 3) 渲染富文本
        final SpannableStringBuilder ssb =
                new SpannableStringBuilder(markwon.toMarkdown(processed));

        // 4) 链接 Span → 可点击 Span
        if (cap.hasLink) {
            replaceLinks(ssb, theme.getLinkColor(), onLinkClick);
        }

        // 5) 图片 Span 回填：已注册默认 loader 时走 AsyncDrawableSpan 真正加载，否则占位块
        if (!images.isEmpty()) {
            applyImagePlaceholders(
                    context, ssb, images, theme,
                    ImageBlockView.getDefaultAsyncDrawableLoader(),
                    markwon.configuration().theme());
        }

        return new MarkdownContent(ssb, root, cap.hasLink, cap.hasTable, cap.hasBold, cap.hasImage);
    }

    // ---------------------------------------------------------------------
    // Markwon 实例获取 / 创建
    // ---------------------------------------------------------------------

    @NonNull
    private static Markwon markwon(
            @NonNull Context context,
            @NonNull MdTheme theme,
            @NonNull MarkdownConfig config,
            @Nullable MarkwonFactory factory) {
        final MarkwonKey key = new MarkwonKey(
                theme.hashCode(),
                config.hashCode(),
                factory != null ? System.identityHashCode(factory) : 0);
        Markwon markwon = MARKWON_CACHE.get(key);
        if (markwon == null) {
            markwon = createMarkwon(context, theme, config, factory);
            MARKWON_CACHE.put(key, markwon);
        }
        return markwon;
    }

    @NonNull
    private static Markwon createMarkwon(
            @NonNull Context context,
            @NonNull MdTheme theme,
            @NonNull MarkdownConfig config,
            @Nullable MarkwonFactory factory) {
        // 业务侧自定义工厂优先
        if (factory != null) {
            final Markwon custom = factory.create(context, theme, config);
            if (custom != null) {
                return custom;
            }
        }
        // 默认管线：core 全能力（主题化）+ 段落/heading 间距 + 表格
        final float density = context.getResources().getDisplayMetrics().density;
        final CorePlugin corePlugin = new CorePlugin() {
            @Override
            public void configureTheme(@NonNull MarkwonTheme.Builder builder) {
                builder
                        // heading 层级更平缓（默认 H1=2.0 过大），正文 15sp 下 H1≈25sp
                        .headingTextSizeMultipliers(new float[]{1.7F, 1.35F, 1.18F, 1.05F, 0.95F, 0.88F})
                        // H1/H2 底部 break 弱化为分割线色
                        .headingBreakColor(theme.getTableBorderColor())
                        .headingBreakHeight(Math.max(1, Math.round(density)))
                        // 列表缩进微收（默认 24dp），bullet/序号用次要色与正文分层
                        .blockMargin(Math.round(20 * density))
                        .listItemColor(theme.getSecondaryTextColor())
                        // quote 竖线：细边框色，宽 3dp
                        .blockQuoteColor(theme.getTableBorderColor())
                        .blockQuoteWidth(Math.round(3 * density))
                        // 行内 code：与代码块同底色 + 圆角 + 红粉文字（豆包风格）
                        .codeBackgroundColor(theme.getCodeBackground())
                        .codeBackgroundRadius(Math.round(4 * density))
                        .codeHorizontalPadding(Math.round(5 * density))
                        .codeTextColor(theme.getInlineCodeTextColor())
                        // 分隔线与边框统一色
                        .thematicBreakColor(theme.getTableBorderColor())
                        .thematicBreakHeight(Math.max(1, Math.round(density)))
                        .linkColor(theme.getLinkColor());
            }
        };

        final TableTheme tableTheme = TableTheme.buildWithDefaults(context)
                .tableCellPadding((int) (10 * density))
                .tableBorderColor(theme.getTableBorderColor())
                .tableBorderWidth(Math.max(1, Math.round(density)))
                .tableHeaderRowBackgroundColor(theme.getTableHeaderColor())
                .tableOddRowBackgroundColor(oddRowColor(theme.getTableHeaderColor()))
                .tableCornerRadius((int) (8 * density))
                .tableScrollEnabled(true)
                .tableMaxColumnWidth((int) (200 * density))
                .tableScrollbarHeight((int) (14 * density))
                .tableScrollbarThumbColor(0xFFCACACA)
                .build();
        return Markwon.builder(context)
                .usePlugin(corePlugin)
                // 覆盖 core 的 Paragraph/Heading visitor：追加段间距与 heading 上下留白
                .usePlugin(new BlockSpacingPlugin(density))
                .usePlugin(TablePlugin.create(tableTheme))
                .build();
    }

    /**
     * 按明暗主题计算表格奇数行底色：浅色主题 → 淡黑（斑马纹），深色主题 → 淡白。
     */
    private static int oddRowColor(@ColorInt int headerColor) {
        final double luminance = (0.299 * Color.red(headerColor)
                + 0.587 * Color.green(headerColor)
                + 0.114 * Color.blue(headerColor)) / 255.0;
        return luminance > 0.5 ? 0x08000000 : 0x0DFFFFFF;
    }

    /**
     * 块间距插件：覆盖 core 的 Paragraph / Heading visitor，
     * 在保持原渲染逻辑（CoreProps + SpanFactory）不变的前提下追加间距 span——
     * <ul>
     *     <li>段落（非 tight list）：末尾 {@link LastLineSpacingSpan} 增加段间距；</li>
     *     <li>heading：首行上方留白（{@link HeadingTopSpacingSpan}）+ 末尾留白，
     *         解决标题与上下文紧贴的问题。</li>
     * </ul>
     * 注册在 {@link CorePlugin} 之后，后注册的 visitor 覆盖先注册的同名节点。
     */
    private static final class BlockSpacingPlugin extends AbstractMarkwonPlugin {

        private final int paragraphSpacingPx;
        private final int headingSpacingPx;

        BlockSpacingPlugin(float density) {
            this.paragraphSpacingPx = Math.round(6f * density);
            this.headingSpacingPx = Math.round(6f * density);
        }

        @Override
        public void configureVisitor(@NonNull MarkwonVisitor.Builder builder) {
            builder.on(Paragraph.class, new MarkwonVisitor.NodeVisitor<Paragraph>() {
                @Override
                public void visit(@NonNull MarkwonVisitor visitor, @NonNull Paragraph paragraph) {
                    final boolean inTightList = isInTightList(paragraph);
                    if (!inTightList) {
                        visitor.blockStart(paragraph);
                    }
                    final int length = visitor.length();
                    visitor.visitChildren(paragraph);
                    CoreProps.PARAGRAPH_IS_IN_TIGHT_LIST.set(visitor.renderProps(), inTightList);
                    visitor.setSpansForNodeOptional(paragraph, length);
                    // 段落间距：末行 descent 下探（仅非 tight list 段落）
                    if (!inTightList && visitor.length() > length) {
                        visitor.builder().setSpan(
                                LastLineSpacingSpan.create(paragraphSpacingPx),
                                length, visitor.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    if (!inTightList) {
                        visitor.blockEnd(paragraph);
                    }
                }
            });
            builder.on(Heading.class, new MarkwonVisitor.NodeVisitor<Heading>() {
                @Override
                public void visit(@NonNull MarkwonVisitor visitor, @NonNull Heading heading) {
                    visitor.blockStart(heading);
                    final int length = visitor.length();
                    visitor.visitChildren(heading);
                    CoreProps.HEADING_LEVEL.set(visitor.renderProps(), heading.getLevel());
                    visitor.setSpansForNodeOptional(heading, length);
                    if (visitor.length() > length) {
                        visitor.builder().setSpan(
                                new HeadingTopSpacingSpan(headingSpacingPx),
                                length, visitor.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        visitor.builder().setSpan(
                                LastLineSpacingSpan.create(headingSpacingPx),
                                length, visitor.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    visitor.blockEnd(heading);
                }
            });
        }

        /** 与 {@link CorePlugin} 同逻辑：tight list 内的段落不加块级间距 */
        private static boolean isInTightList(@NonNull Paragraph paragraph) {
            final Node parent = paragraph.getParent();
            if (parent != null) {
                final Node gramps = parent.getParent();
                if (gramps instanceof ListBlock) {
                    return ((ListBlock) gramps).isTight();
                }
            }
            return false;
        }
    }

    /**
     * heading 首行上方留白：行盒 ascent/top 上扩，使标题与上文内容之间有呼吸感。
     */
    private static final class HeadingTopSpacingSpan implements LineHeightSpan {

        private final int spacing;

        HeadingTopSpacingSpan(int spacing) {
            this.spacing = spacing;
        }

        @Override
        public void chooseHeight(
                @NonNull CharSequence text,
                int start, int end,
                int spanstartv, int v,
                @NonNull Paint.FontMetricsInt fm) {
            final int spanStart = ((Spanned) text).getSpanStart(this);
            // 首行判定：本行 start 与 span 起点重合（换行符偏移 ±1 容忍）
            if (start == spanStart || start == spanStart - 1) {
                fm.ascent -= spacing;
                fm.top -= spacing;
            }
        }
    }

    private static final class MarkwonKey {

        final int themeHash;
        final int configHash;
        final int factoryIdentity;

        MarkwonKey(int themeHash, int configHash, int factoryIdentity) {
            this.themeHash = themeHash;
            this.configHash = configHash;
            this.factoryIdentity = factoryIdentity;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof MarkwonKey)) {
                return false;
            }
            final MarkwonKey key = (MarkwonKey) o;
            return themeHash == key.themeHash
                    && configHash == key.configHash
                    && factoryIdentity == key.factoryIdentity;
        }

        @Override
        public int hashCode() {
            int result = themeHash;
            result = 31 * result + configHash;
            result = 31 * result + factoryIdentity;
            return result;
        }
    }

    // ---------------------------------------------------------------------
    // 能力探测
    // ---------------------------------------------------------------------

    private static final class Capabilities {
        boolean hasLink;
        boolean hasTable;
        boolean hasBold;
        boolean hasImage;
    }

    private static void walkCapabilities(@NonNull Node node, @NonNull Capabilities cap) {
        if (cap.hasLink && cap.hasTable && cap.hasBold && cap.hasImage) {
            return;
        }
        if (node instanceof Link) {
            cap.hasLink = true;
        } else if (node instanceof Image) {
            cap.hasImage = true;
        } else if (node instanceof StrongEmphasis) {
            cap.hasBold = true;
        } else if (node instanceof TableBlock) {
            cap.hasTable = true;
        }
        Node child = node.getFirstChild();
        while (child != null) {
            walkCapabilities(child, cap);
            child = child.getNext();
        }
    }

    // ---------------------------------------------------------------------
    // 图片预处理 + 占位 Span
    // ---------------------------------------------------------------------

    private static final class ImageMark {
        @NonNull
        final String url;
        @NonNull
        final String alt;

        ImageMark(@NonNull String url, @NonNull String alt) {
            this.url = url;
            this.alt = alt;
        }
    }

    /** 把行内 ![]() 替换为 □ 占位符，同时记录 alt/url 供回填 Span */
    @NonNull
    private static String preProcessImages(@NonNull String text, @NonNull List<ImageMark> out) {
        final Matcher matcher = BlockContentExtractor.IMAGE_MARKDOWN.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        final StringBuilder sb = new StringBuilder(text.length());
        int cursor = 0;
        do {
            sb.append(text, cursor, matcher.start());
            sb.append(IMAGE_PLACEHOLDER);
            out.add(new ImageMark(
                    matcher.group(2),
                    matcher.group(1) == null ? "" : matcher.group(1)));
            cursor = matcher.end();
        } while (matcher.find());
        sb.append(text, cursor, text.length());
        return sb.toString();
    }

    private static void applyImagePlaceholders(
            @NonNull Context context,
            @NonNull SpannableStringBuilder ssb,
            @NonNull List<ImageMark> images,
            @NonNull MdTheme theme,
            @Nullable AsyncDrawableLoader loader,
            @Nullable MarkwonTheme markwonTheme) {
        int from = 0;
        int idx = 0;
        while (idx < images.size()) {
            final int pos = ssb.toString().indexOf(IMAGE_PLACEHOLDER, from);
            if (pos < 0 || pos >= ssb.length()) {
                break;
            }
            final ImageMark img = images.get(idx++);
            if (loader != null && markwonTheme != null) {
                // 真正加载：AsyncDrawable + loader 管线（行内宽度在绘制时按可用宽度决定）
                final AsyncDrawable drawable =
                        new AsyncDrawable(img.url, loader, new ImageSizeResolverDef(), null);
                ssb.setSpan(
                        new AsyncDrawableSpan(
                                markwonTheme, drawable, AsyncDrawableSpan.ALIGN_CENTER, true),
                        pos, pos + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                // 未注册 loader：占位块兜底
                ssb.setSpan(
                        new ImagePlaceholderSpan(context, img.alt, img.url, theme),
                        pos, pos + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            from = pos + 1;
        }
    }

    /**
     * 图片占位 Span：圆角灰底 + 🖼 alt / url 两行文案（复刻豆包 PlaceholderImageSpan）。
     * 接入真实加载库后替换为 AsyncDrawable/Glide 等即可。
     */
    @SuppressWarnings("deprecation")
    public static final class ImagePlaceholderSpan extends ReplacementSpan {

        private static final float WIDTH_DP = 140f;
        private static final float HEIGHT_DP = 64f;

        private final float density;
        private final int bgColor;
        private final int textColor;
        private final float cornerRadius;
        private final String text;
        private final int width;
        private final int height;

        public ImagePlaceholderSpan(
                @NonNull Context context,
                @NonNull String alt,
                @NonNull String url,
                @NonNull MdTheme theme) {
            final float d = context.getResources().getDisplayMetrics().density;
            this.density = d;
            this.bgColor = theme.getCodeBackground();
            this.textColor = theme.getSecondaryTextColor();
            this.cornerRadius = 12f * d;
            this.text = "\uD83D\uDDBC\uFE0F " + alt + "\n" + url;
            this.width = (int) (WIDTH_DP * d);
            this.height = (int) (HEIGHT_DP * d);
        }

        @Override
        public int getSize(@NonNull Paint paint, CharSequence charSequence, int start, int end,
                           @Nullable Paint.FontMetricsInt fm) {
            if (fm != null) {
                // 让行盒高度 = 占位块高度（上下居中）
                final int half = height / 2;
                fm.ascent = -half;
                fm.descent = half;
                fm.top = fm.ascent;
                fm.bottom = fm.descent;
            }
            return width;
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence charSequence, int start, int end,
                         float x, int top, int y, int bottom, @NonNull Paint paint) {
            // 圆角灰底
            final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            bgPaint.setColor(bgColor);
            final RectF box = new RectF(x, top, x + width, bottom);
            canvas.drawRoundRect(box, cornerRadius, cornerRadius, bgPaint);

            // 两行文案居中
            final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            tp.setTextSize(13f * density);
            tp.setColor(textColor);
            final StaticLayout layout = new StaticLayout(
                    text, tp, width - (int) (16 * density),
                    Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false);
            canvas.save();
            canvas.translate(x + (width - layout.getWidth()) / 2f,
                    top + (height - layout.getHeight()) / 2f);
            layout.draw(canvas);
            canvas.restore();
        }
    }

    // ---------------------------------------------------------------------
    // 链接 Span 替换
    // ---------------------------------------------------------------------

    private static void replaceLinks(
            @NonNull SpannableStringBuilder ssb,
            @ColorInt int linkColor,
            @Nullable OnLinkClick onLinkClick) {
        final LinkSpan[] spans = ssb.getSpans(0, ssb.length(), LinkSpan.class);
        if (spans.length == 0) {
            return;
        }
        for (LinkSpan span : spans) {
            final int start = ssb.getSpanStart(span);
            final int end = ssb.getSpanEnd(span);
            final int flags = ssb.getSpanFlags(span);
            final String url = span.getLink();
            ssb.removeSpan(span);
            if (url == null || url.isEmpty()) {
                continue;
            }
            ssb.setSpan(new UrlClickableSpan(url, linkColor, onLinkClick), start, end, flags);
        }
    }

    /**
     * 可点击链接 Span：优先回调 {@link OnLinkClick}，否则系统浏览器打开（失败复制兜底）。
     */
    public static final class UrlClickableSpan extends ClickableSpan {

        @NonNull
        private final String url;
        @ColorInt
        private final int linkColor;
        @Nullable
        private final OnLinkClick onLinkClick;

        public UrlClickableSpan(
                @NonNull String url,
                @ColorInt int linkColor,
                @Nullable OnLinkClick onLinkClick) {
            this.url = url;
            this.linkColor = linkColor;
            this.onLinkClick = onLinkClick;
        }

        @Override
        public void onClick(@NonNull View widget) {
            if (onLinkClick != null) {
                onLinkClick.onClick(url);
            } else {
                LinkHandler.handleClick(widget.getContext(), url);
            }
        }

        @Override
        public void updateDrawState(@NonNull TextPaint ds) {
            ds.setColor(linkColor);
            // 豆包风格：链接仅用颜色区分，不加下划线
            ds.setUnderlineText(false);
        }
    }
}