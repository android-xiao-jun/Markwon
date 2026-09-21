package io.noties.markwon.block.view;

/**
 * 视图回收接口（对应豆包 CustomMarkdownTextView.g()）。
 *
 * <p>装配器在回收/移除子视图前调用 {@link #onViewRecycled()}，
 * 各块视图借此清掉动画回调、占位文本等，避免泄漏与重影。
 */
public interface ViewRecycler {

    void onViewRecycled();
}