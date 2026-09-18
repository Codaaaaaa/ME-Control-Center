package io.github.codaaaaaa.mecc.core.resources;

import java.util.Objects;

/**
 * A run of a display name that shares one style. A name is a list of these; most names are a single
 * unstyled span, and only those that carry colour or emphasis need more.
 *
 * @param color {@code #rrggbb}, or {@code null} for the terminal's own text colour
 */
public record NameSpan(String text, String color, boolean bold, boolean italic, boolean underlined, boolean strikethrough) {

    public NameSpan {
        Objects.requireNonNull(text, "text");
    }

    public static NameSpan plain(String text) {
        return new NameSpan(text, null, false, false, false, false);
    }

    public boolean styled() {
        return color != null || bold || italic || underlined || strikethrough;
    }

    /** Whether two spans differ only in their text and can therefore be joined. */
    public boolean sameStyle(NameSpan other) {
        return Objects.equals(color, other.color) && bold == other.bold && italic == other.italic
                && underlined == other.underlined && strikethrough == other.strikethrough;
    }
}
