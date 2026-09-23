package io.noties.markwon.block.render;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 代码块复制按钮点击回调。
 *
 * <p>外部设置后「接管」复制行为，返回值三态语义：
 * <ul>
 *     <li>未设置 listener → 本地默认处理（{@code LinkHandler#copyToClipboard} + 按钮状态）；</li>
 *     <li>返回 {@code true} → 外部已处理复制，内部仅更新按钮状态（“已复制”绿字 + 定时复位）；</li>
 *     <li>返回 {@code false} → 拦截本次点击，不做任何事（不复制、不改状态）。</li>
 * </ul>
 *
 * <p>代码块视图在 RecyclerView 复用池中可能被复用，建议用
 * {@link io.noties.markwon.block.view.CodeBlockView#setDefaultCopyClickListener(CodeBlockCopyListener)}
 * 注册全局默认；实例级 {@code setOnCopyClickListener} 可覆盖单个视图。
 */
public interface CodeBlockCopyListener {

    /**
     * @param code     代码内容（不含行号，仅源码）
     * @param language 语言标签（无语言时为 null）
     * @return true 外部接管复制（内部只更新按钮状态）；false 拦截本次点击
     */
    boolean onCopyClick(@NonNull String code, @Nullable String language);
}