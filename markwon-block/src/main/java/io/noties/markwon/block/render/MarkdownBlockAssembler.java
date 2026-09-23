package io.noties.markwon.block.render;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.noties.markwon.block.model.Block;
import io.noties.markwon.block.model.BlockContentExtractor;
import io.noties.markwon.block.view.AbsBlockView;
import io.noties.markwon.block.view.BlockViewFactory;
import io.noties.markwon.block.view.CodeBlockView;
import io.noties.markwon.block.view.CustomMarkdownTextView;
import io.noties.markwon.block.view.ImageBlockView;
import io.noties.markwon.block.view.SeparatorBlockView;
import io.noties.markwon.block.view.ViewRecycler;

/**
 * 块装配器（复刻豆包 MarkdownAssembler）。
 *
 * <p>每次输入都「整体重 parse → 视图树差分复用」：
 * <ul>
 *     <li>块级公共前缀（类型 + 文本均相同）对应的子视图直接复用、跳过绑定；</li>
 *     <li>非文本块当块类型变化时按「同类复用 / 异类重建」处理；</li>
 *     <li>当前块增量（新文本以旧文本为前缀）：仅新后缀淡入，保留打字机效果；</li>
 *     <li>多余子视图回收（{@link ViewRecycler#onViewRecycled()}）。</li>
 * </ul>
 *
 * <p>文本缓存 keyed by 视图键，装配器内部持有，供跨次渲染做 diff。
 */
public final class MarkdownBlockAssembler {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** 视图键 → 上次渲染的整段文本 */
    private final Map<String, String> textCache = new ConcurrentHashMap<>();

    /**
     * 组装：把 {@code newText} 渲染进 {@code container} 的子视图。
     *
     * @param container 块容器（通常为 {@code MarkdownTextBlockView} 自身）
     * @param viewKey   稳定标识当前容器（跨次渲染做 diff 的 key）
     * @param newText   本次要渲染的整段 markdown
     * @param markdownWidthPx 可用宽度（表格 EXACTLY 用，&lt;=0 时回退默认测量）
     * @param streaming 打字机态（仅最后一个文本块挂光标）
     */
    public void assemble(
            @NonNull Context context,
            @NonNull ViewGroup container,
            @NonNull String viewKey,
            @NonNull String newText,
            @NonNull MdTheme theme,
            @NonNull MarkdownConfig config,
            @Nullable MarkwonFactory markwonFactory,
            @Nullable BlockViewFactory viewFactory,
            @Nullable OnLinkClick onLinkClick,
            int markdownWidthPx,
            boolean streaming) {
        final List<Block> newBlocks = BlockContentExtractor.parse(newText);
        final String prevText = textCache.get(viewKey);
        final List<Block> prevBlocks =
                prevText != null ? BlockContentExtractor.parse(prevText) : Collections.emptyList();
        final int common = longestCommonBlocks(newBlocks, prevBlocks);

        // 1) 子视图对齐块列表（不足则创建；类型不匹配则重建）
        for (int i = 0; i < newBlocks.size(); i++) {
            ensureChild(context, container, i, newBlocks.get(i), theme, config, viewFactory);
        }
        // 1.1) 块间统一呼吸间距（首块贴顶，其余块顶部 8dp）
        applyBlockSpacing(context, container);
        // 2) 回收多余子视图
        for (int i = container.getChildCount() - 1; i >= newBlocks.size(); i--) {
            final View v = container.getChildAt(i);
            if (v instanceof ViewRecycler) {
                ((ViewRecycler) v).onViewRecycled();
            }
            container.removeViewAt(i);
        }
        // 3) 绑定
        for (int i = 0; i < newBlocks.size(); i++) {
            if (i < common) {
                continue; // 未变化块：复用跳过
            }
            final Block block = newBlocks.get(i);
            final View child = container.getChildAt(i);
            if (block instanceof Block.TextBlock) {
                if (!(child instanceof CustomMarkdownTextView)) {
                    continue; // 防御：类型不匹配时跳过
                }
                final String text = ((Block.TextBlock) block).getText();
                int fadeFrom = 0;
                // 同一块内增量：旧文本是新文本前缀 → 仅新后缀淡入
                if (i == common && i < prevBlocks.size()) {
                    final Block prev = prevBlocks.get(i);
                    if (prev instanceof Block.TextBlock) {
                        final String prevBlockText = ((Block.TextBlock) prev).getText();
                        if (text.startsWith(prevBlockText) && text.length() > prevBlockText.length()) {
                            fadeFrom = prevBlockText.length();
                        }
                    }
                }
                final boolean isLast = i == newBlocks.size() - 1;
                final MarkdownContent content = MarkdownRenderer.render(
                        context, text, theme, config, markwonFactory, onLinkClick);
                ((CustomMarkdownTextView) child)
                        .setMarkdownContent(content, markdownWidthPx, streaming && isLast, fadeFrom);
            } else if (child instanceof AbsBlockView) {
                ((AbsBlockView) child).bindData(block, null);
            }
        }

        textCache.put(viewKey, newText);
    }

    /** 计算两个块列表的公共前缀长度（TextBlock 比较文本，其余比较类型） */
    private static int longestCommonBlocks(@NonNull List<Block> a, @NonNull List<Block> b) {
        int i = 0;
        final int n = Math.min(a.size(), b.size());
        while (i < n) {
            final Block x = a.get(i);
            final Block y = b.get(i);
            final boolean same;
            if (x instanceof Block.TextBlock && y instanceof Block.TextBlock) {
                same = ((Block.TextBlock) x).getText().equals(((Block.TextBlock) y).getText());
            } else {
                same = x.getBlockType() == y.getBlockType();
            }
            if (!same) {
                break;
            }
            i++;
        }
        return i;
    }

    /** 保证容器第 index 位是 block 类型的渲染视图：同类复用 / 异类重建 / 自定义工厂每次重建 */
    private void ensureChild(
            @NonNull Context context,
            @NonNull ViewGroup container,
            int index,
            @NonNull Block block,
            @NonNull MdTheme theme,
            @NonNull MarkdownConfig config,
            @Nullable BlockViewFactory viewFactory) {
        final int type = block.getBlockType();
        if (container.getChildCount() > index) {
            final View existing = container.getChildAt(index);
            if (viewFactory == null && typeMatches(existing, type)) {
                return; // 复用
            }
            if (existing instanceof ViewRecycler) {
                ((ViewRecycler) existing).onViewRecycled();
            }
            container.removeViewAt(index);
        }
        final View created;
        if (type == Block.TYPE_TEXT) {
            created = new CustomMarkdownTextView(context, theme, config);
        } else if (viewFactory != null) {
            created = viewFactory.createView(context, type, theme, config);
        } else {
            created = BlockViewFactory.createDefaultView(context, type, theme, config);
        }
        container.addView(created, index,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /**
     * 块间统一间距：第 0 块贴顶（top=0），其余块顶部 8dp。
     * 每次装配后统一刷新，保证流式增块 / 块移除后间距仍然正确。
     */
    private static void applyBlockSpacing(@NonNull Context context, @NonNull ViewGroup container) {
        final int spacing = Math.round(8f * context.getResources().getDisplayMetrics().density);
        for (int i = 0; i < container.getChildCount(); i++) {
            final View child = container.getChildAt(i);
            final ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (lp instanceof LinearLayout.LayoutParams) {
                final LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) lp;
                final int top = i == 0 ? 0 : spacing;
                if (llp.topMargin != top) {
                    llp.topMargin = top;
                    child.setLayoutParams(llp);
                }
            }
        }
    }

    private static boolean typeMatches(@NonNull View view, int type) {
        switch (type) {
            case Block.TYPE_TEXT:
                return view instanceof CustomMarkdownTextView;
            case Block.TYPE_CODE:
                return view instanceof CodeBlockView;
            case Block.TYPE_IMAGE:
                return view instanceof ImageBlockView;
            case Block.TYPE_SEPARATOR:
                return view instanceof SeparatorBlockView;
            default:
                return false;
        }
    }
}