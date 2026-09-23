package io.noties.markwon.block.render;

import android.graphics.Color;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

import java.util.Arrays;

/**
 * 渲染主题（复刻豆包 lf8.r：文本色 / 次要文本色 / 链接色 / 代码背景 / 边框 / 光标色）
 *
 * <p>字段与 demo 的 MdTheme 一一对应，提供 light（默认） / dark / subscribed（白字订阅主题）
 * 三种预设，以及 Builder 自由配置。
 */
public final class MdTheme {

    @NonNull
    private final String name;
    @ColorInt
    private final int textColor;
    @ColorInt
    private final int secondaryTextColor;
    @ColorInt
    private final int linkColor;
    @ColorInt
    private final int codeBackground;
    @ColorInt
    private final int codeHeaderBackground;
    @ColorInt
    private final int codeBorderColor;
    @ColorInt
    private final int textBackground;
    @ColorInt
    private final int tableHeaderColor;
    @ColorInt
    private final int tableBorderColor;
    @ColorInt
    private final int typingCursorColor;
    /** 行内 code 文字色（豆包/GitHub 风格红粉系，light 深红 / dark 亮粉） */
    @ColorInt
    private final int inlineCodeTextColor;
    /** 流式新增字符淡入开关 */
    private final boolean alphaFade;

    private MdTheme(@NonNull Builder builder) {
        this.name = builder.name;
        this.textColor = builder.textColor;
        this.secondaryTextColor = builder.secondaryTextColor;
        this.linkColor = builder.linkColor;
        this.codeBackground = builder.codeBackground;
        this.codeHeaderBackground = builder.codeHeaderBackground;
        this.codeBorderColor = builder.codeBorderColor;
        this.textBackground = builder.textBackground;
        this.tableHeaderColor = builder.tableHeaderColor;
        this.tableBorderColor = builder.tableBorderColor;
        this.typingCursorColor = builder.typingCursorColor;
        this.inlineCodeTextColor = builder.inlineCodeTextColor;
        this.alphaFade = builder.alphaFade;
    }

    @NonNull
    public static MdTheme light() {
        return new Builder().build();
    }

    @NonNull
    public static MdTheme dark() {
        return new Builder()
                .name("dark")
                .textColor(Color.parseColor("#E8E8EE"))
                .secondaryTextColor(Color.parseColor("#9A9FA8"))
                .linkColor(Color.parseColor("#6E9BFF"))
                .codeBackground(Color.parseColor("#1B1D22"))
                .codeHeaderBackground(Color.parseColor("#22252B"))
                .codeBorderColor(Color.parseColor("#2E3239"))
                .tableHeaderColor(Color.parseColor("#2A3142"))
                .tableBorderColor(Color.parseColor("#2E3239"))
                .typingCursorColor(Color.parseColor("#6E9BFF"))
                .inlineCodeTextColor(Color.parseColor("#FF8A9E"))
                .build();
    }

    /** 豆包订阅白色主题（useSubscribedColor） */
    @NonNull
    public static MdTheme subscribed() {
        return new Builder()
                .name("subscribed")
                .textColor(Color.WHITE)
                .secondaryTextColor(Color.parseColor("#D8DAE0"))
                .linkColor(Color.parseColor("#9DBFFF"))
                .codeBackground(Color.parseColor("#2A2D32"))
                .codeHeaderBackground(Color.parseColor("#32363C"))
                .codeBorderColor(Color.parseColor("#3F444C"))
                .tableHeaderColor(Color.parseColor("#343B4D"))
                .tableBorderColor(Color.parseColor("#3F444C"))
                .typingCursorColor(Color.WHITE)
                .inlineCodeTextColor(Color.parseColor("#FF8A9E"))
                .build();
    }

    @NonNull
    public String getName() {
        return name;
    }

    @ColorInt
    public int getTextColor() {
        return textColor;
    }

    @ColorInt
    public int getSecondaryTextColor() {
        return secondaryTextColor;
    }

    @ColorInt
    public int getLinkColor() {
        return linkColor;
    }

    @ColorInt
    public int getCodeBackground() {
        return codeBackground;
    }

    @ColorInt
    public int getCodeHeaderBackground() {
        return codeHeaderBackground;
    }

    @ColorInt
    public int getCodeBorderColor() {
        return codeBorderColor;
    }

    @ColorInt
    public int getTextBackground() {
        return textBackground;
    }

    @ColorInt
    public int getTableHeaderColor() {
        return tableHeaderColor;
    }

    @ColorInt
    public int getTableBorderColor() {
        return tableBorderColor;
    }

    @ColorInt
    public int getTypingCursorColor() {
        return typingCursorColor;
    }

    @ColorInt
    public int getInlineCodeTextColor() {
        return inlineCodeTextColor;
    }

    public boolean isAlphaFade() {
        return alphaFade;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(new Object[]{
                name, textColor, secondaryTextColor, linkColor, codeBackground,
                codeHeaderBackground, codeBorderColor, textBackground,
                tableHeaderColor, tableBorderColor, typingCursorColor,
                inlineCodeTextColor, alphaFade
        });
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final MdTheme theme = (MdTheme) o;
        return textColor == theme.textColor
                && secondaryTextColor == theme.secondaryTextColor
                && linkColor == theme.linkColor
                && codeBackground == theme.codeBackground
                && codeHeaderBackground == theme.codeHeaderBackground
                && codeBorderColor == theme.codeBorderColor
                && textBackground == theme.textBackground
                && tableHeaderColor == theme.tableHeaderColor
                && tableBorderColor == theme.tableBorderColor
                && typingCursorColor == theme.typingCursorColor
                && inlineCodeTextColor == theme.inlineCodeTextColor
                && alphaFade == theme.alphaFade
                && name.equals(theme.name);
    }

    public static final class Builder {

        private String name = "light";
        private int textColor = Color.parseColor("#1F2329");
        private int secondaryTextColor = Color.parseColor("#646A73");
        private int linkColor = Color.parseColor("#3370FF");
        private int codeBackground = Color.parseColor("#F2F3F5");
        private int codeHeaderBackground = Color.parseColor("#E8EAED");
        private int codeBorderColor = Color.parseColor("#D9DDE3");
        private int textBackground = Color.TRANSPARENT;
        private int tableHeaderColor = Color.parseColor("#E9EFFB");
        private int tableBorderColor = Color.parseColor("#D9DDE3");
        private int typingCursorColor = Color.parseColor("#3370FF");
        private int inlineCodeTextColor = Color.parseColor("#C7384A");
        private boolean alphaFade = true;

        @NonNull
        public Builder name(@NonNull String name) {
            this.name = name;
            return this;
        }

        @NonNull
        public Builder textColor(@ColorInt int color) {
            this.textColor = color;
            return this;
        }

        @NonNull
        public Builder secondaryTextColor(@ColorInt int color) {
            this.secondaryTextColor = color;
            return this;
        }

        @NonNull
        public Builder linkColor(@ColorInt int color) {
            this.linkColor = color;
            return this;
        }

        @NonNull
        public Builder codeBackground(@ColorInt int color) {
            this.codeBackground = color;
            return this;
        }

        @NonNull
        public Builder codeHeaderBackground(@ColorInt int color) {
            this.codeHeaderBackground = color;
            return this;
        }

        @NonNull
        public Builder codeBorderColor(@ColorInt int color) {
            this.codeBorderColor = color;
            return this;
        }

        @NonNull
        public Builder textBackground(@ColorInt int color) {
            this.textBackground = color;
            return this;
        }

        @NonNull
        public Builder tableHeaderColor(@ColorInt int color) {
            this.tableHeaderColor = color;
            return this;
        }

        @NonNull
        public Builder tableBorderColor(@ColorInt int color) {
            this.tableBorderColor = color;
            return this;
        }

        @NonNull
        public Builder typingCursorColor(@ColorInt int color) {
            this.typingCursorColor = color;
            return this;
        }

        @NonNull
        public Builder inlineCodeTextColor(@ColorInt int color) {
            this.inlineCodeTextColor = color;
            return this;
        }

        @NonNull
        public Builder alphaFade(boolean alphaFade) {
            this.alphaFade = alphaFade;
            return this;
        }

        @NonNull
        public MdTheme build() {
            return new MdTheme(this);
        }
    }
}