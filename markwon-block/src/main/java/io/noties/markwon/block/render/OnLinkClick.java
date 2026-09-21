package io.noties.markwon.block.render;

import androidx.annotation.NonNull;

/**
 * 链接点击回调（复刻豆包 CustomMarkdownTextView 内 JumpAnalyzer 的跳转回调）
 *
 * <p>未设置时走 {@link io.noties.markwon.block.view.LinkHandler} 的默认行为
 * （系统浏览器打开，失败降级复制）。
 */
public interface OnLinkClick {

    void onClick(@NonNull String url);
}