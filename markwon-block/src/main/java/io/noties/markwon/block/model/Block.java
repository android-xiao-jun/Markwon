package io.noties.markwon.block.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 消息块结构化（复刻豆包 Block 体系）
 *
 * <p>IM 消息 = 多条结构化 Block；每条在 UI 层对应一个块视图，
 * 由 {@link io.noties.markwon.block.view.BlockViewFactory} 分发。
 *
 * <p>流式（streaming）场景下 {@link BlockContentExtractor#parse(String)}
 * 每次整体重构块列表，块的数量随文本增长而增长；装配器按
 * 「同类复用 / 异类重建」策略复用旧视图树，实现增量渲染。
 */
public abstract class Block {

    public static final int TYPE_TEXT = 1;
    public static final int TYPE_CODE = 2;
    public static final int TYPE_IMAGE = 3;
    public static final int TYPE_SEPARATOR = 4;

    private final int blockType;
    private final String blockId;

    /** 块在整段 markdown 中的起始 offset（流式淡入定位用） */
    private int startInclusive;

    Block(int blockType, @NonNull String blockId) {
        this.blockType = blockType;
        this.blockId = blockId;
    }

    public final int getBlockType() {
        return blockType;
    }

    @NonNull
    public final String getBlockId() {
        return blockId;
    }

    final void setStartInclusive(int startInclusive) {
        this.startInclusive = startInclusive;
    }

    /**
     * 块在整段 markdown 中的起始 offset（流式淡入定位用；由
     * {@link BlockContentExtractor#parse(String)} 填充）。
     */
    public final int getStartInclusive() {
        return startInclusive;
    }

    /** 文本块：原始 markdown 文本（含行内图片语法），渲染为 MarkdownTextView */
    public static class TextBlock extends Block {

        private final String text;

        public TextBlock(@NonNull String blockId, @NonNull String text) {
            super(TYPE_TEXT, blockId);
            this.text = text;
        }

        @NonNull
        public String getText() {
            return text;
        }

        @Override
        public String toString() {
            return "TextBlock{text=" + text + '}';
        }
    }

    /** 代码块：```fence，渲染为 CodeBlockView（复制按钮 + 行号 + 横向滚动） */
    public static class CodeBlock extends Block {

        @Nullable
        private final String language;
        @NonNull
        private final String code;

        public CodeBlock(@NonNull String blockId, @Nullable String language, @NonNull String code) {
            super(TYPE_CODE, blockId);
            this.language = language;
            this.code = code;
        }

        @Nullable
        public String getLanguage() {
            return language;
        }

        @NonNull
        public String getCode() {
            return code;
        }

        @Override
        public String toString() {
            return "CodeBlock{language=" + language + '}';
        }
    }

    /** 图片块：独立一行的 ![](url)，渲染为 ImageBlockView（占位，接入加载库） */
    public static class ImageBlock extends Block {

        @NonNull
        private final String url;
        @NonNull
        private final String alt;

        public ImageBlock(@NonNull String blockId, @NonNull String url, @NonNull String alt) {
            super(TYPE_IMAGE, blockId);
            this.url = url;
            this.alt = alt;
        }

        @NonNull
        public String getUrl() {
            return url;
        }

        @NonNull
        public String getAlt() {
            return alt;
        }

        @Override
        public String toString() {
            return "ImageBlock{url=" + url + '}';
        }
    }

    /** 分隔线块：仅画一条线 */
    public static class SeparatorBlock extends Block {

        public SeparatorBlock(@NonNull String blockId) {
            super(TYPE_SEPARATOR, blockId);
        }
    }
}