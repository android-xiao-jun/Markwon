package io.noties.markwon.block.view;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;

import io.noties.markwon.block.model.Block;

/**
 * 图片块视图（复刻豆包 ImageBlock / GenImageBlock 的 BlockView）。
 *
 * <p>本库不内置图片加载器：独立行图片渲染为占位块（🖼 + alt + url）。
 * 接入真实加载库时，把占位替换为 ImageView + 加载器即可，页签规范不变。
 */
public class ImageBlockView extends AbsBlockView implements ViewRecycler {

    private TextView imageText;
    private String url = "";
    private String alt = "";

    public ImageBlockView(@NonNull Context context) {
        super(context);
    }

    @Override
    protected void initView(@NonNull Context context, @Nullable AttributeSet attrs) {
        final float density = getResources().getDisplayMetrics().density;
        setPadding(
                (int) (16 * density), (int) (8 * density),
                (int) (16 * density), (int) (8 * density));

        imageText = new TextView(context);
        imageText.setGravity(Gravity.CENTER);
        imageText.setTextSize(13f);
        imageText.setTextColor(Color.parseColor("#8A8F99"));
        imageText.setPadding(0, (int) (36 * density), 0, (int) (36 * density));
        imageText.setClickable(true);

        final GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(Color.parseColor("#F2F3F5"));
        bg.setCornerRadius(10f * density);
        imageText.setBackground(bg);

        addView(imageText,
                new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    @Override
    public void bindData(@NonNull Block block, @Nullable Map<String, Object> payload) {
        if (!(block instanceof Block.ImageBlock)) {
            return;
        }
        final Block.ImageBlock img = (Block.ImageBlock) block;
        url = img.getUrl();
        alt = img.getAlt();
        imageText.setText("\uD83D\uDDBC\uFE0F " + alt + "\n" + url);
        // TODO(接入图片加载): Glide/Coil 等把 url 加载到 ImageView，替换占位块
    }

    @Override
    public void onViewRecycled() {
        if (imageText != null) {
            imageText.setText("");
        }
        url = "";
        alt = "";
    }
}