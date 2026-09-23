package io.noties.markwon.block.view;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;

import io.noties.markwon.block.model.Block;
import io.noties.markwon.block.render.OnImageClick;
import io.noties.markwon.image.AsyncDrawable;
import io.noties.markwon.image.AsyncDrawableLoader;
import io.noties.markwon.image.AsyncDrawableLoaderBuilder;
import io.noties.markwon.image.ImageSizeResolverDef;

/**
 * 图片块视图（复刻豆包 ImageBlock / GenImageBlock 的 BlockView）。
 *
 * <p>图片加载复用 markwon 原框架能力（markwon-image 的异步加载管线）：
 * <ul>
 *     <li><b>未设置加载器</b>：渲染为可点击占位块（🖼 + alt + url），点击回调 {@link OnImageClick}；</li>
 *     <li><b>设置了 {@link AsyncDrawableLoader}</b>（全局
 *         {@link #setDefaultAsyncDrawableLoader(AsyncDrawableLoader)} 或实例
 *         {@link #setAsyncDrawableLoader(AsyncDrawableLoader)}）：走
 *         {@link AsyncDrawable} + loader 管线，加载中显示圆角灰底、完成后替换为图片。</li>
 * </ul>
 *
 * <p>loader 的两种来源：
 * <ul>
 *     <li>markwon-image 默认：{@link #defaultMarkwonLoader()}（data-uri / network + SVG / GIF decoder）；</li>
 *     <li>markwon-image-glide：把 {@code GlideImagesPlugin} 装配进 Markwon 后，取
 *         {@code markwon.configuration().asyncDrawableLoader()} 传入（动态切换，无需改块视图）。</li>
 * </ul>
 */
public class ImageBlockView extends AbsBlockView implements ViewRecycler {

    /** 全局默认图片加载器（markwon-image / markwon-image-glide 均可注入） */
    @Nullable
    private static AsyncDrawableLoader sLoader;

    /** 实例级加载器，优先于全局默认 */
    @Nullable
    private AsyncDrawableLoader loader;

    /** 全局默认图片点击回调 */
    @Nullable
    private static OnImageClick sOnImageClick;

    /** 实例级图片点击回调，优先于全局默认 */
    @Nullable
    private OnImageClick onImageClick;

    private TextView fallbackText;
    private ImageView imageView;

    private String url = "";
    private String alt = "";

    /** 当前在途的异步图片（onViewRecycled 时 detach 以触发 loader.cancel） */
    @Nullable
    private AsyncDrawable currentDrawable;

    public ImageBlockView(@NonNull Context context) {
        super(context);
    }

    /**
     * markwon-image 的默认加载器实现（内部自带 data-uri / network scheme handler，
     * 以及 SVG / GIF / DefaultMediaDecoder），开箱即用。
     */
    @NonNull
    public static AsyncDrawableLoader defaultMarkwonLoader() {
        return new AsyncDrawableLoaderBuilder().build();
    }

    /**
     * 当前全局默认加载器（可能为 null：未注册时图片渲染为占位块）。
     * 供渲染管线（如行内图片生成的 {@code AsyncDrawableSpan}）复用同一个 loader。
     */
    @Nullable
    public static AsyncDrawableLoader getDefaultAsyncDrawableLoader() {
        return sLoader;
    }

    /**
     * 注册全局默认图片加载器。未设置时图片块渲染为占位块。
     */
    public static void setDefaultAsyncDrawableLoader(@Nullable AsyncDrawableLoader loader) {
        sLoader = loader;
    }

    /**
     * 设置实例级图片加载器（优先于全局默认；传 null 回落占位块）。
     */
    public void setAsyncDrawableLoader(@Nullable AsyncDrawableLoader loader) {
        this.loader = loader;
    }

    /**
     * 注册全局默认图片点击回调。
     */
    public static void setDefaultOnImageClick(@Nullable OnImageClick listener) {
        sOnImageClick = listener;
    }

    /**
     * 设置实例级图片点击回调（优先于全局默认）。
     */
    public void setOnImageClick(@Nullable OnImageClick listener) {
        this.onImageClick = listener;
    }

    @Override
    protected void initView(@NonNull Context context, @Nullable AttributeSet attrs) {
        final float density = getResources().getDisplayMetrics().density;

        // 多样式占位 Text：加载中 / 未加载时的可见兜底
        fallbackText = new TextView(context);
        fallbackText.setGravity(Gravity.CENTER);
        fallbackText.setTextSize(13f);
        fallbackText.setTextColor(Color.parseColor("#8A8F99"));
        fallbackText.setPadding(0, (int) (36 * density), 0, (int) (36 * density));
        setBoxBackground(fallbackText, density);
        addView(fallbackText,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        // 图片承载 View：加载器存在时使用（加载中显示灰底，加载完成替换为图片）
        imageView = new ImageView(context);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setPadding(0, (int) (12 * density), 0, (int) (12 * density));
        imageView.setMinimumHeight((int) (120 * density));
        setBoxBackground(imageView, density);
        imageView.setVisibility(GONE);
        addView(imageView,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        final View.OnClickListener click = v -> {
            final OnImageClick listener =
                    onImageClick != null ? onImageClick : sOnImageClick;
            if (listener != null) {
                listener.onClick(url, alt);
            }
        };
        fallbackText.setOnClickListener(click);
        imageView.setOnClickListener(click);
    }

    private static void setBoxBackground(@NonNull View view, float density) {
        final GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(Color.parseColor("#F2F3F5"));
        bg.setCornerRadius(10f * density);
        view.setBackground(bg);
    }

    @Override
    public void bindData(@NonNull Block block, @Nullable Map<String, Object> payload) {
        if (!(block instanceof Block.ImageBlock)) {
            return;
        }
        final Block.ImageBlock img = (Block.ImageBlock) block;
        url = img.getUrl() == null ? "" : img.getUrl();
        alt = img.getAlt() == null ? "" : img.getAlt();

        if (url.isEmpty()) {
            showFallback("\uD83D\uDDBC\uFE0F " + alt);
            return;
        }

        final AsyncDrawableLoader resolved = loader != null ? loader : sLoader;
        if (resolved == null) {
            showFallback("\uD83D\uDDBC\uFE0F " + alt + "\n" + url);
            return;
        }

        // 走 markwon-image 异步加载管线：AsyncDrawable + loader
        showFallback(null);
        currentDrawable = new AsyncDrawable(url, resolved, new ImageSizeResolverDef(), null);
        imageView.setImageDrawable(currentDrawable);
        // attach 触发 loader.load（非 deferred 且无结果时发起请求）
        currentDrawable.setCallback2(imageView);
    }

    /** 占位文本可见（text 为 null 表示隐藏占位、展示图片承载 View） */
    private void showFallback(@Nullable String text) {
        final boolean visible = text != null;
        fallbackText.setVisibility(visible ? VISIBLE : GONE);
        if (visible) {
            fallbackText.setText(text);
        }
        imageView.setVisibility(visible ? GONE : VISIBLE);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        // 把容器宽度喂给 AsyncDrawable：尺寸解析（占满宽度、按比例伸缩）依赖 canvasWidth
        final AsyncDrawable drawable = currentDrawable;
        if (drawable != null
                && imageView.getWidth() > 0
                && !drawable.hasKnownDimensions()) {
            drawable.initWithKnownDimensions(imageView.getWidth(), 0);
        }
    }

    @Override
    public void onViewRecycled() {
        // detach → AsyncDrawable 内部触发 loader.cancel
        if (currentDrawable != null) {
            currentDrawable.setCallback2(null);
            currentDrawable = null;
        }
        if (imageView != null) {
            imageView.setImageDrawable(null);
        }
        if (fallbackText != null) {
            fallbackText.setText("");
        }
        url = "";
        alt = "";
    }
}