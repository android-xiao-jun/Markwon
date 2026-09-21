package io.noties.markwon.block.view;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;

import io.noties.markwon.block.model.Block;
import io.noties.markwon.block.render.MarkdownConfig;
import io.noties.markwon.block.render.MdTheme;

/**
 * 块视图工厂（复刻豆包 BlockViewFactory）：把「渲染方法」插拔给外部。
 *
 * <p>装配器按块类型取渲染视图时，优先走业务侧自定义工厂；
 * 未设置时使用 {@link Default} 内置分发：
 * <ul>
 *     <li>CODE → {@link CodeBlockView}；</li>
 *     <li>IMAGE → {@link ImageBlockView}；</li>
 *     <li>SEPARATOR → {@link SeparatorBlockView}；</li>
 *     <li>其余 → {@link CustomMarkdownTextView}（文本兜底）。</li>
 * </ul>
 */
public interface BlockViewFactory {

    /**
     * @param context   视图上下文
     * @param blockType {@link Block#getBlockType()} 的值
     * @param theme     当前主题
     * @param config    当前配置
     * @return 该块对应的渲染视图
     */
    @NonNull
    View createView(
            @NonNull Context context,
            int blockType,
            @NonNull MdTheme theme,
            @NonNull MarkdownConfig config);

    /**
     * 内置默认分发。
     */
    @NonNull
    static View createDefaultView(
            @NonNull Context context,
            int blockType,
            @NonNull MdTheme theme,
            @NonNull MarkdownConfig config) {
        return Default.INSTANCE.createView(context, blockType, theme, config);
    }

    final class Default implements BlockViewFactory {

        public static final Default INSTANCE = new Default();

        private Default() {
        }

        @NonNull
        @Override
        public View createView(
                @NonNull Context context,
                int blockType,
                @NonNull MdTheme theme,
                @NonNull MarkdownConfig config) {
            switch (blockType) {
                case Block.TYPE_CODE:
                    return new CodeBlockView(context, theme, config);
                case Block.TYPE_IMAGE:
                    return new ImageBlockView(context);
                case Block.TYPE_SEPARATOR:
                    return new SeparatorBlockView(context);
                default:
                    return new CustomMarkdownTextView(context, theme, config);
            }
        }
    }
}