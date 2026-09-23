package io.noties.markwon.core;

import io.noties.markwon.Prop;
import io.noties.markwon.core.scroll.CodeBlockScrollState;

/**
 * @since 3.0.0
 */
public abstract class CoreProps {

    public static final Prop<ListItemType> LIST_ITEM_TYPE = Prop.of("list-item-type");

    public static final Prop<Integer> BULLET_LIST_ITEM_LEVEL = Prop.of("bullet-list-item-level");

    public static final Prop<Integer> ORDERED_LIST_ITEM_NUMBER = Prop.of("ordered-list-item-number");

    public static final Prop<Integer> HEADING_LEVEL = Prop.of("heading-level");

    public static final Prop<String> LINK_DESTINATION = Prop.of("link-destination");

    public static final Prop<Boolean> PARAGRAPH_IS_IN_TIGHT_LIST = Prop.of("paragraph-is-in-tight-list");

    /**
     * @since 4.1.1
     */
    public static final Prop<String> CODE_BLOCK_INFO = Prop.of("code-block-info");

    /**
     * <b>Original</b> source of the code block being rendered (the fenced/indented literal,
     * before syntax highlighting). Set by {@code CorePlugin} while visiting the node, read by
     * {@code CodeBlockSpanFactory} — it is what the copy button of a scrollable block puts
     * into the clipboard, so the text the user copies is the text the author wrote and not
     * the highlighted {@code Spanned} (which carries extra spans and sentinel characters).
     *
     * @since 4.6.3
     */
    public static final Prop<String> CODE_BLOCK_CODE = Prop.of("code-block-code");

    /**
     * Scroll state shared by all the lines of the code block currently being rendered.
     * Set by {@code CorePlugin} right before the spans are applied, read by
     * {@code CodeBlockSpanFactory}.
     *
     * @since 4.6.3
     */
    public static final Prop<CodeBlockScrollState> CODE_BLOCK_SCROLL_STATE =
            Prop.of("code-block-scroll-state");

    /**
     * <b>Original</b> literal of the inline code currently being rendered. Set by
     * {@code CorePlugin} while visiting the node, read by {@code CodeSpanFactory}
     * to decide whether the rounded pill ({@code CodeRoundedSpan}) can be applied:
     * a {@code ReplacementSpan} is one unbreakable unit, so a long literal (path,
     * URL, command) overflows its line/table column instead of wrapping — those
     * must fall back to the wrap-friendly plain {@code CodeSpan}.
     *
     * @since 4.6.3
     */
    public static final Prop<String> CODE_TEXT = Prop.of("code-text");

    public enum ListItemType {
        BULLET,
        ORDERED
    }

    private CoreProps() {
    }
}
