package io.noties.markwon.block.render;

import android.content.Context;

import androidx.annotation.NonNull;

import io.noties.markwon.Markwon;

/**
 * Markwon 实例工厂：把「解析方法」插拔给外部。
 *
 * <p>业务方可以自定义 Markwon 的插件集合（如语法高亮 / 更多扩展），
 * 只需返回一个可用的 {@link Markwon} 实例；返回 {@code null} 时回退到
 * 内置默认管线（core 全能力 + 表格）。
 */
public interface MarkwonFactory {

    /**
     * @param context Application Context 即可
     * @param theme   当前渲染主题（可据此配置链接色、表格色等）
     * @param config  当前渲染配置
     * @return 自定义 Markwon 实例；允许返回 {@code null} 表示走默认管线
     */
    Markwon create(@NonNull Context context, @NonNull MdTheme theme, @NonNull MarkdownConfig config);
}