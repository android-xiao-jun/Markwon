package io.noties.markwon.block.render;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;

/**
 * 渲染配置（复刻豆包 lf8.j：Flag 驱动的全部开关）
 *
 * <p>核心字段：
 * <ul>
 *     <li>{@link #isStreaming()} 流式（打字机）态：启用增量装配 + 打字光标；</li>
 *     <li>{@link #isEnableCodeBlockStroke()} 代码块描边；</li>
 *     <li>{@link #isEnableCodeBlockHeaderBackground()} 代码块 header 背景；</li>
 *     <li>{@link #isEnableTableHeader()} 表格表头强调。</li>
 * </ul>
 */
public final class MarkdownConfig {

    /** 失败 / 打断态 */
    private final boolean failOrInterrupt;
    /** 流式（打字机）状态：启用增量装配 + 打字光标 */
    private final boolean isStreaming;
    private final boolean isDeepThinkArea;
    private final boolean isDeepResearchArea;
    /** 订阅会员色（豆包白色订阅主题） */
    private final boolean useSubscribedColor;
    private final boolean useCodeStyle;
    /** 代码块描边 */
    private final boolean enableCodeBlockStroke;
    /** 代码块 header 背景 */
    private final boolean enableCodeBlockHeaderBackground;
    /** 表格表头强调 */
    private final boolean enableTableHeader;
    /** 强制刷新 id：配置变化时重走 bind */
    @Nullable
    private final String forceRefreshId;

    private MarkdownConfig(@NonNull Builder builder) {
        this.failOrInterrupt = builder.failOrInterrupt;
        this.isStreaming = builder.isStreaming;
        this.isDeepThinkArea = builder.isDeepThinkArea;
        this.isDeepResearchArea = builder.isDeepResearchArea;
        this.useSubscribedColor = builder.useSubscribedColor;
        this.useCodeStyle = builder.useCodeStyle;
        this.enableCodeBlockStroke = builder.enableCodeBlockStroke;
        this.enableCodeBlockHeaderBackground = builder.enableCodeBlockHeaderBackground;
        this.enableTableHeader = builder.enableTableHeader;
        this.forceRefreshId = builder.forceRefreshId;
    }

    public boolean isFailOrInterrupt() {
        return failOrInterrupt;
    }

    public boolean isStreaming() {
        return isStreaming;
    }

    public boolean isDeepThinkArea() {
        return isDeepThinkArea;
    }

    public boolean isDeepResearchArea() {
        return isDeepResearchArea;
    }

    public boolean isUseSubscribedColor() {
        return useSubscribedColor;
    }

    public boolean isUseCodeStyle() {
        return useCodeStyle;
    }

    public boolean isEnableCodeBlockStroke() {
        return enableCodeBlockStroke;
    }

    public boolean isEnableCodeBlockHeaderBackground() {
        return enableCodeBlockHeaderBackground;
    }

    public boolean isEnableTableHeader() {
        return enableTableHeader;
    }

    @Nullable
    public String getForceRefreshId() {
        return forceRefreshId;
    }

    /** 流式状态下追加时使用：copy 一份并切换 streaming 开关（对应 demo copyWith） */
    @NonNull
    public MarkdownConfig withStreaming(boolean streaming) {
        if (streaming == isStreaming) {
            return this;
        }
        return new Builder()
                .failOrInterrupt(failOrInterrupt)
                .streaming(streaming)
                .deepThinkArea(isDeepThinkArea)
                .deepResearchArea(isDeepResearchArea)
                .subscribedColor(useSubscribedColor)
                .codeStyle(useCodeStyle)
                .codeBlockStroke(enableCodeBlockStroke)
                .codeBlockHeaderBackground(enableCodeBlockHeaderBackground)
                .tableHeader(enableTableHeader)
                .forceRefreshId(forceRefreshId)
                .build();
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(new Object[]{
                failOrInterrupt, isStreaming, isDeepThinkArea, isDeepResearchArea,
                useSubscribedColor, useCodeStyle, enableCodeBlockStroke,
                enableCodeBlockHeaderBackground, enableTableHeader, forceRefreshId
        });
    }

    public static final class Builder {

        private boolean failOrInterrupt = false;
        private boolean isStreaming = false;
        private boolean isDeepThinkArea = false;
        private boolean isDeepResearchArea = false;
        private boolean useSubscribedColor = false;
        private boolean useCodeStyle = true;
        private boolean enableCodeBlockStroke = true;
        private boolean enableCodeBlockHeaderBackground = true;
        private boolean enableTableHeader = true;
        @Nullable
        private String forceRefreshId;

        @NonNull
        public Builder failOrInterrupt(boolean value) {
            this.failOrInterrupt = value;
            return this;
        }

        @NonNull
        public Builder streaming(boolean value) {
            this.isStreaming = value;
            return this;
        }

        @NonNull
        public Builder deepThinkArea(boolean value) {
            this.isDeepThinkArea = value;
            return this;
        }

        @NonNull
        public Builder deepResearchArea(boolean value) {
            this.isDeepResearchArea = value;
            return this;
        }

        @NonNull
        public Builder subscribedColor(boolean value) {
            this.useSubscribedColor = value;
            return this;
        }

        @NonNull
        public Builder codeStyle(boolean value) {
            this.useCodeStyle = value;
            return this;
        }

        @NonNull
        public Builder codeBlockStroke(boolean value) {
            this.enableCodeBlockStroke = value;
            return this;
        }

        @NonNull
        public Builder codeBlockHeaderBackground(boolean value) {
            this.enableCodeBlockHeaderBackground = value;
            return this;
        }

        @NonNull
        public Builder tableHeader(boolean value) {
            this.enableTableHeader = value;
            return this;
        }

        @NonNull
        public Builder forceRefreshId(@Nullable String forceRefreshId) {
            this.forceRefreshId = forceRefreshId;
            return this;
        }

        @NonNull
        public MarkdownConfig build() {
            return new MarkdownConfig(this);
        }
    }
}