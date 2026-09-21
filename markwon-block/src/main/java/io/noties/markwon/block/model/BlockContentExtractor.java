package io.noties.markwon.block.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本抠取 / 块结构化（复刻豆包 xd8.a 清洗 + 块分发切分）
 *
 * <p>{@link #parse(String)} 将一段 markdown 文本结构化为 {@link Block} 列表：
 * <ul>
 *     <li>```fence 代码块 → {@link Block.CodeBlock}；</li>
 *     <li>独立一行的 ![](url) → {@link Block.ImageBlock}；</li>
 *     <li>其余文本 → {@link Block.TextBlock}。</li>
 * </ul>
 * 每次调用都对整段文本做结构化，块列表随流式文本增长而增长。
 */
public final class BlockContentExtractor {

    /**
     * 行内图片正则（MarkdownRenderer 用它把 ![](url) 替换为占位 □ + 占位图 Span；
     * 注意：独立行图片已被 parse() 抽为 ImageBlock，这里只处理行内/文本段内的图片）。
     */
    public static final Pattern IMAGE_MARKDOWN =
            Pattern.compile("!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)");

    /**
     * 块级扫描正则：fence 代码块（优先）| 独立行图片。
     * 组1=语言、组2=代码、组3=alt、组4=url。
     * <p>访问组前必须先判 null（单组不参与匹配时 group() 返回 null），
     * 否则会抛 IllegalStateException / StringIndexOutOfBoundsException。
     */
    private static final Pattern BLOCK_MATCHER = Pattern.compile(
            "```([^\\n]*)\\r?\\n([\\s\\S]*?)(?:\\r?\\n)?```"
                    + "|(?m)^[ \\t]*!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)[ \\t]*$");

    private BlockContentExtractor() {
    }

    /**
     * 把一段 markdown 文本结构化为块列表。
     * 空块（纯空白文本段、空代码）会被丢弃，保证块列表仅含可渲染内容。
     *
     * @param markdown 原始 markdown 文本
     * @return 结构化后的 Block 列表（顺序与原文一致）
     */
    @NonNull
    public static List<Block> parse(@NonNull String markdown) {
        final List<Block> out = new ArrayList<>();
        final Matcher matcher = BLOCK_MATCHER.matcher(markdown);
        int cursor = 0;
        int seq = 0;
        while (matcher.find()) {
            addTextIfNotBlank(out, markdown, cursor, matcher.start(), seq++);
            if (matcher.group(2) != null) {
                // fence 代码块
                final String language = matcher.group(1);
                final String code = trimTrailingEmptyLines(matcher.group(2));
                if (!code.trim().isEmpty()) {
                    final Block.CodeBlock block = new Block.CodeBlock("code-" + seq, language, code);
                    block.setStartInclusive(matcher.start());
                    out.add(block);
                }
            } else if (matcher.group(4) != null) {
                // 独立行图片
                final String alt = matcher.group(3) == null ? "" : matcher.group(3);
                final String url = matcher.group(4);
                final Block.ImageBlock block = new Block.ImageBlock("image-" + seq, url, alt);
                block.setStartInclusive(matcher.start());
                out.add(block);
            }
            cursor = matcher.end();
        }
        addTextIfNotBlank(out, markdown, cursor, markdown.length(), seq);
        return out;
    }

    private static void addTextIfNotBlank(@NonNull List<Block> out, @NonNull String markdown, int from, int to, int seq) {
        if (from >= to) {
            return;
        }
        while (to > from && Character.isWhitespace(markdown.charAt(to - 1))) {
            to--;
        }
        while (from < to && Character.isWhitespace(markdown.charAt(from))) {
            from++;
        }
        if (from >= to) {
            return;
        }
        final String text = markdown.substring(from, to);
        if (text.trim().isEmpty()) {
            return;
        }
        final Block.TextBlock block = new Block.TextBlock("text-" + seq, text);
        block.setStartInclusive(from);
        out.add(block);
    }

    @NonNull
    private static String trimTrailingEmptyLines(@NonNull String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
            end--;
        }
        return end == s.length() ? s : s.substring(0, end);
    }

    /**
     * 从文本块抠出可渲染的 markdown 文本（本库模型无 media 回插，直接返回原文）。
     *
     * @param block 文本块（其余类型返回空串）
     */
    @NonNull
    public static String extractText(@Nullable Block block) {
        return block instanceof Block.TextBlock ? ((Block.TextBlock) block).getText() : "";
    }
}