package io.noties.markwon.block.view;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;

import io.noties.markwon.block.model.Block;

/**
 * 块视图基类（复刻豆包 BlockView）。
 *
 * <p>每个结构化 {@link Block} 对应一个 AbsBlockView 实现：
 * <ul>
 *     <li>{@link MarkdownTextBlockView}：整段 markdown 容器 / 一键入口；</li>
 *     <li>{@link CustomMarkdownTextView}：文本块渲染；</li>
 *     <li>{@link CodeBlockView}：代码块（复制 + 行号 + 横向滚动）；</li>
 *     <li>{@link ImageBlockView}：独立行图片占位；</li>
 *     <li>{@link SeparatorBlockView}：分隔线。</li>
 * </ul>
 *
 * <p>自定义块视图只要继承本类并实现 {@link #initView(Context, AttributeSet)}
 * 与 {@link #bindData(Block, Map)} 即可被 {@link BlockViewFactory} 分发。
 */
public abstract class AbsBlockView extends LinearLayout {

    /** bind payload 中传递主题的 key */
    public static final String KEY_THEME = "theme";
    /** bind payload 中传递配置的 key */
    public static final String KEY_CONFIG = "config";

    protected AbsBlockView(@NonNull Context context) {
        this(context, null);
    }

    protected AbsBlockView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    protected AbsBlockView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOrientation(LinearLayout.VERTICAL);
        initView(context, attrs);
    }

    /**
     * 初始化子视图。子类构造时调用（构建自己的控件树）。
     */
    protected abstract void initView(@NonNull Context context, @Nullable AttributeSet attrs);

    /**
     * 绑定数据：把块内容落到视图上。默认为空实现（TextBlock 走内置渲染路径）。
     *
     * @param block   当前块
     * @param payload 附加数据（主题/配置等，可为 null）
     */
    public void bindData(@NonNull Block block, @Nullable Map<String, Object> payload) {
        // 默认空实现
    }

    /**
     * 视图回收时清理：默认空实现，需要清理的子类自行覆写。
     */
    public void onViewRecycled() {
        // 默认空实现
    }
}