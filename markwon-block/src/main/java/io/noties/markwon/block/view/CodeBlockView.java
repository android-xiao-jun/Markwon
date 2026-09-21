package io.noties.markwon.block.view;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;

import io.noties.markwon.block.model.Block;
import io.noties.markwon.block.render.MarkdownConfig;
import io.noties.markwon.block.render.MdTheme;

/**
 * 代码块视图（复刻豆包 CodeBlockView）。
 *
 * <p>结构：header（语言 + 复制按钮）+ 横向滚动代码区；
 * 行号（3 位 + {@link ForegroundColorSpan} 次要色）、MONOSPACE 13sp。
 */
public class CodeBlockView extends AbsBlockView implements ViewRecycler {

    private static final int ROUND = 8;
    private static final long COPY_RESET_MS = 1200L;

    private final MdTheme theme;
    private final MarkdownConfig config;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextView headerText;
    private TextView copyButton;
    private TextView codeText;
    private String code = "";

    private final Runnable resetCopy = new Runnable() {
        @Override
        public void run() {
            copyButton.setText("复制");
            copyButton.setTextColor(theme.getSecondaryTextColor());
        }
    };

    public CodeBlockView(
            @NonNull Context context,
            @NonNull MdTheme theme,
            @NonNull MarkdownConfig config) {
        super(context);
        this.theme = theme;
        this.config = config;
        buildViews();
    }

    @Override
    protected void initView(@NonNull Context context, @Nullable AttributeSet attrs) {
        // 控件树依赖 theme/config 字段，故在构造器完成（见 buildViews）
    }

    private void buildViews() {
        final float density = getResources().getDisplayMetrics().density;

        // 根背景：圆角 + 可选描边
        final GradientDrawable rootBg = new GradientDrawable();
        rootBg.setShape(GradientDrawable.RECTANGLE);
        rootBg.setColor(theme.getCodeBackground());
        rootBg.setCornerRadius(ROUND * density);
        if (config.isEnableCodeBlockStroke()) {
            rootBg.setStroke(Math.max(1, Math.round(density)), theme.getCodeBorderColor());
        }
        setBackground(rootBg);

        // header：语言 + 复制按钮
        final LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        headerText = new TextView(getContext());
        headerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        headerText.setTextColor(theme.getSecondaryTextColor());
        headerText.setSingleLine(true);
        headerText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        headerText.setPadding((int) (12 * density), (int) (8 * density), (int) (8 * density), (int) (8 * density));
        header.addView(headerText, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        copyButton = new TextView(getContext());
        copyButton.setText("复制");
        copyButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        copyButton.setTextColor(theme.getSecondaryTextColor());
        copyButton.setGravity(Gravity.CENTER);
        copyButton.setPadding((int) (12 * density), (int) (8 * density), (int) (12 * density), (int) (8 * density));
        copyButton.setClickable(true);
        copyButton.setOnClickListener(v -> onCopyClick());
        header.addView(copyButton, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        if (config.isEnableCodeBlockHeaderBackground()) {
            final GradientDrawable headerBg = new GradientDrawable();
            headerBg.setShape(GradientDrawable.RECTANGLE);
            headerBg.setColor(theme.getCodeHeaderBackground());
            // 仅顶部两角圆角（底部与代码区相接）
            headerBg.setCornerRadii(new float[]{
                    ROUND * density, ROUND * density, ROUND * density, ROUND * density,
                    0f, 0f, 0f, 0f});
            header.setBackground(headerBg);
        }
        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // 代码区：横向滚动
        final HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        codeText = new TextView(getContext());
        codeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        codeText.setTypeface(Typeface.MONOSPACE);
        codeText.setTextColor(theme.getTextColor());
        codeText.setPadding((int) (12 * density), (int) (10 * density), (int) (12 * density), (int) (14 * density));
        scroll.addView(codeText, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    private void onCopyClick() {
        LinkHandler.copyToClipboard(getContext(), code);
        copyButton.setText("已复制");
        copyButton.setTextColor(Color.parseColor("#1BB24E"));
        mainHandler.removeCallbacks(resetCopy);
        mainHandler.postDelayed(resetCopy, COPY_RESET_MS);
    }

    @Override
    public void bindData(@NonNull Block block, @Nullable Map<String, Object> payload) {
        if (!(block instanceof Block.CodeBlock)) {
            return;
        }
        final Block.CodeBlock cb = (Block.CodeBlock) block;
        code = cb.getCode() == null ? "" : cb.getCode();
        final String language = cb.getLanguage();
        headerText.setText(language == null || language.trim().isEmpty() ? "代码" : language.trim());
        bindCodeText();
    }

    /** 拼行号 + 代码：行号为 3 位次要色，代码为主色 */
    private void bindCodeText() {
        final String[] lines = code.split("\n", -1);
        final SpannableStringBuilder sb = new SpannableStringBuilder();
        final int numColor = theme.getSecondaryTextColor();
        for (int i = 0; i < lines.length; i++) {
            final String num = String.format("%3d", i + 1);
            final int start = sb.length();
            sb.append(num).append("  ");
            sb.setSpan(new ForegroundColorSpan(numColor),
                    start, start + num.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append(lines[i]);
            sb.append('\n');
        }
        // 去掉末尾多余换行
        final int len = sb.length();
        if (len > 0 && sb.charAt(len - 1) == '\n') {
            sb.delete(len - 1, len);
        }
        codeText.setText(sb, TextView.BufferType.SPANNABLE);
    }

    @Override
    public void onViewRecycled() {
        mainHandler.removeCallbacks(resetCopy);
        if (copyButton != null) {
            copyButton.setText("复制");
            copyButton.setTextColor(theme.getSecondaryTextColor());
        }
        if (codeText != null) {
            codeText.setText("");
        }
        if (headerText != null) {
            headerText.setText("");
        }
        code = "";
    }
}