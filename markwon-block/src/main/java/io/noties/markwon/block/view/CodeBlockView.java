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

import io.noties.markwon.block.R;
import io.noties.markwon.block.model.Block;
import io.noties.markwon.block.render.CodeBlockCopyListener;
import io.noties.markwon.block.render.MarkdownConfig;
import io.noties.markwon.block.render.MdTheme;

/**
 * 代码块视图（复刻豆包 CodeBlockView）。
 *
 * <p>结构：header（语言 + 复制按钮）+ 横向滚动代码区；
 * 行号（3 位 + {@link ForegroundColorSpan} 次要色）、MONOSPACE 13sp。
 *
 * <p>复制按钮点击可被外部接管：全局默认
 * {@link #setDefaultCopyClickListener(CodeBlockCopyListener)} 或实例级
 * {@link #setOnCopyClickListener(CodeBlockCopyListener)}。语义见
 * {@link CodeBlockCopyListener}：返回 true 外部处理（内部只改按钮状态）、
 * false 拦截、未设置时本地默认复制。
 */
public class CodeBlockView extends AbsBlockView implements ViewRecycler {

    private static final int ROUND = 8;
    private static final long COPY_RESET_MS = 1200L;

    /** 全局默认复制回调（RecyclerView 复用场景建议用它注册） */
    @Nullable
    private static CodeBlockCopyListener sCopyListener;

    /**
     * 注册全局默认复制回调。未设置时复制按钮走本地默认（剪贴板 + 按钮状态）；
     * 已设置时每次点击回调三态语义见 {@link CodeBlockCopyListener}。
     */
    public static void setDefaultCopyClickListener(@Nullable CodeBlockCopyListener listener) {
        sCopyListener = listener;
    }

    /** 全局默认文案（null = 回退 strings.xml，随 locale 切换），可覆盖多语言 */
    @Nullable
    private static String sCopyText;
    @Nullable
    private static String sCopiedText;
    @Nullable
    private static String sDefaultLanguageText;

    /** 设置全局复制按钮文案。传 null 恢复 strings.xml 默认（支持多语言资源覆盖）。 */
    public static void setDefaultCopyText(@Nullable String text) {
        sCopyText = text;
    }

    /** 设置全局复制成功反馈文案。传 null 恢复 strings.xml 默认。 */
    public static void setDefaultCopiedText(@Nullable String text) {
        sCopiedText = text;
    }

    /** 设置全局代码块默认语言标题（未标注语言时用）。传 null 恢复 strings.xml 默认。 */
    public static void setDefaultLanguageText(@Nullable String text) {
        sDefaultLanguageText = text;
    }

    private final MdTheme theme;
    private final MarkdownConfig config;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 实例级复制回调，优先于全局默认 */
    @Nullable
    private CodeBlockCopyListener copyListener;

    /**
     * 设置实例级复制回调（优先于 {@link #setDefaultCopyClickListener} 的全局默认）。
     * 传 null 可清除，回落本地默认。
     */
    public void setOnCopyClickListener(@Nullable CodeBlockCopyListener listener) {
        this.copyListener = listener;
    }

    private TextView headerText;
    private TextView copyButton;
    private TextView codeText;
    private String code = "";
    private String language = "";

    /** 文案优先级：外部静态设置 > strings.xml 资源（随 locale，支持多语言覆盖） */
    @NonNull
    private String copyText() {
        final String t = sCopyText;
        return t != null ? t : getContext().getString(R.string.markwon_block_code_copy);
    }

    @NonNull
    private String copiedText() {
        final String t = sCopiedText;
        return t != null ? t : getContext().getString(R.string.markwon_block_code_copied);
    }

    @NonNull
    private String defaultLanguageText() {
        final String t = sDefaultLanguageText;
        return t != null ? t : getContext().getString(R.string.markwon_block_code_default_language);
    }

    private final Runnable resetCopy = new Runnable() {
        @Override
        public void run() {
            copyButton.setText(copyText());
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
        copyButton.setText(copyText());
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
        // 已设置的实例回调优先于全局默认
        final CodeBlockCopyListener listener =
                copyListener != null ? copyListener : sCopyListener;
        if (listener != null) {
            final boolean handled = listener.onCopyClick(code, language);
            if (!handled) {
                return; // 拦截：不复制、不改按钮状态
            }
            // true：外部已接管复制，内部仅更新按钮状态
            showCopiedFeedback();
            return;
        }
        // 未设置 listener：本地默认复制
        LinkHandler.copyToClipboard(getContext(), code);
        showCopiedFeedback();
    }

    /** 复制后的按钮反馈（“已复制”绿字 + 定时复位），与复制行为解耦 */
    private void showCopiedFeedback() {
        copyButton.setText(copiedText());
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
        language = cb.getLanguage();
        headerText.setText(language == null || language.trim().isEmpty()
                ? defaultLanguageText() : language.trim());
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
            copyButton.setText(copyText());
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