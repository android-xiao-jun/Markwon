package io.noties.markwon.block.render;

import android.text.SpannableStringBuilder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.commonmark.node.Node;

/**
 * Markdown 渲染结果（复刻豆包 MarkwonContent）
 *
 * <p>字段：
 * <ul>
 *     <li>{@link #getSpannable()} 最终富文本（可直接 setText）；</li>
 *     <li>{@link #getRootNode()} markdown AST 根（能力探测缓存，可为空）；</li>
 *     <li>{@link #hasLink()}/{@link #hasTable()}/{@link #hasBold()}/{@link #hasImage()}
 *     节点级能力探测结果（hasTable 决定表格 invalidator 接线）。</li>
 * </ul>
 */
public final class MarkdownContent {

    @NonNull
    private final SpannableStringBuilder spannable;
    @Nullable
    private final Node rootNode;
    private final boolean hasLink;
    private final boolean hasTable;
    private final boolean hasBold;
    private final boolean hasImage;

    public MarkdownContent(
            @NonNull SpannableStringBuilder spannable,
            @Nullable Node rootNode,
            boolean hasLink,
            boolean hasTable,
            boolean hasBold,
            boolean hasImage) {
        this.spannable = spannable;
        this.rootNode = rootNode;
        this.hasLink = hasLink;
        this.hasTable = hasTable;
        this.hasBold = hasBold;
        this.hasImage = hasImage;
    }

    @NonNull
    public SpannableStringBuilder getSpannable() {
        return spannable;
    }

    @Nullable
    public Node getRootNode() {
        return rootNode;
    }

    public boolean hasLink() {
        return hasLink;
    }

    public boolean hasTable() {
        return hasTable;
    }

    public boolean hasBold() {
        return hasBold;
    }

    public boolean hasImage() {
        return hasImage;
    }
}