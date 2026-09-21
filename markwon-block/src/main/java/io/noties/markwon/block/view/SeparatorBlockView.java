package io.noties.markwon.block.view;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;

import io.noties.markwon.block.model.Block;

/**
 * 分隔线块视图：一条细线（复刻豆包 SeparatorBlockView）。
 */
public class SeparatorBlockView extends AbsBlockView {

    private static final int LINE_COLOR = Color.parseColor("#E5E6EB");

    public SeparatorBlockView(@NonNull Context context) {
        super(context);
    }

    @Override
    protected void initView(@NonNull Context context, @Nullable AttributeSet attrs) {
        final View line = new View(context);
        line.setBackgroundColor(LINE_COLOR);
        final float density = getResources().getDisplayMetrics().density;
        final int height = Math.max(1, (int) (density + 0.5f));
        final LayoutParams lp =
                new LayoutParams(LayoutParams.MATCH_PARENT, height);
        lp.topMargin = (int) (16 * density);
        lp.bottomMargin = (int) (16 * density);
        lp.leftMargin = (int) (16 * density);
        lp.rightMargin = (int) (16 * density);
        addView(line, lp);
    }

    @Override
    public void bindData(@NonNull Block block, @Nullable Map<String, Object> payload) {
        // 分隔线无需绑定数据
    }
}