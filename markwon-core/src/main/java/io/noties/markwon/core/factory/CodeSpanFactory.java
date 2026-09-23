package io.noties.markwon.core.factory;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.noties.markwon.MarkwonConfiguration;
import io.noties.markwon.RenderProps;
import io.noties.markwon.SpanFactory;
import io.noties.markwon.core.CoreProps;
import io.noties.markwon.core.MarkwonTheme;
import io.noties.markwon.core.spans.CodeRoundedSpan;
import io.noties.markwon.core.spans.CodeSpan;

public class CodeSpanFactory implements SpanFactory {

    /**
     * @since 4.6.3 — {@link CodeRoundedSpan} is a {@code ReplacementSpan}: the whole
     * code literal is one <b>unbreakable</b> glyph, so once it is wider than the line
     * (or a narrow table column) it simply overflows it — it cannot wrap, not even at
     * its own spaces. Literals longer than this many characters fall back to the plain
     * wrap-friendly {@link CodeSpan} (a {@code MetricAffectingSpan}, the text wraps
     * character-by-character when needed). Short code keeps the rounded pill look.
     */
    private static final int MAX_ROUNDED_CODE_CHARS = 20;

    @Nullable
    @Override
    public Object getSpans(@NonNull MarkwonConfiguration configuration, @NonNull RenderProps props) {
        final MarkwonTheme theme = configuration.theme();
        // @since 4.6.3 — when a positive radius is requested, CodeRoundedSpan
        // takes over rendering (it draws a rounded background and the glyphs
        // itself). The plain CodeSpan keeps the original TextView-managed
        // background via TextPaint#bgColor.
        if (theme.getCodeBackgroundRadius() > 0) {
            final String code = CoreProps.CODE_TEXT.get(props);
            if (code == null || code.length() <= MAX_ROUNDED_CODE_CHARS) {
                return new CodeRoundedSpan(theme);
            }
        }
        return new CodeSpan(theme);
    }
}
