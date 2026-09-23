package io.noties.markwon.block.render;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 图片块（独立行图片占位）点击回调。
 *
 * <p>本库不内置图片加载器，独立行图片渲染为可点击占位块；外部接入真实加载 /
 * 查看大图时，用 {@link io.noties.markwon.block.view.ImageBlockView#setDefaultOnImageClick(OnImageClick)}
 * 注册全局默认（或实例级 setter）接管点击。
 */
public interface OnImageClick {

    void onClick(@NonNull String url, @Nullable String alt);
}