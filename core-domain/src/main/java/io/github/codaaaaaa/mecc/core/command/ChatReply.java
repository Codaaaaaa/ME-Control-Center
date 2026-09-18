package io.github.codaaaaaa.mecc.core.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Platform-neutral chat output of an in-game command. The platform adapter renders it into its own
 * text component type.
 */
public record ChatReply(boolean success, List<Line> lines) {

    public ChatReply {
        lines = List.copyOf(lines);
    }

    public enum Style {
        NORMAL,
        MUTED,
        ACCENT,
        SUCCESS,
        WARNING,
        ERROR,
        /** A secret the player should copy, e.g. a pairing key. */
        SECRET
    }

    /**
     * @param copyText text copied to the clipboard when clicked, or {@code null}
     * @param url      URL opened when clicked, or {@code null}
     */
    public record Span(String text, Style style, String copyText, String url) {
        public Span {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(style, "style");
        }
    }

    public record Line(List<Span> spans) {
        public Line {
            spans = List.copyOf(spans);
        }
    }

    public static Builder ok() {
        return new Builder(true);
    }

    public static Builder fail() {
        return new Builder(false);
    }

    public static ChatReply error(String message) {
        return fail().line().text(message, Style.ERROR).build();
    }

    public static final class Builder {
        private final boolean success;
        private final List<Line> lines = new ArrayList<>();
        private List<Span> current;

        private Builder(boolean success) {
            this.success = success;
        }

        public Builder line() {
            flush();
            current = new ArrayList<>();
            return this;
        }

        public Builder text(String text, Style style) {
            return span(new Span(text, style, null, null));
        }

        public Builder copyable(String text, Style style, String copyText) {
            return span(new Span(text, style, copyText, null));
        }

        public Builder link(String text, String url) {
            return span(new Span(text, Style.ACCENT, null, url));
        }

        public ChatReply build() {
            flush();
            return new ChatReply(success, lines);
        }

        private Builder span(Span span) {
            if (current == null) {
                current = new ArrayList<>();
            }
            current.add(span);
            return this;
        }

        private void flush() {
            if (current != null) {
                lines.add(new Line(current));
                current = null;
            }
        }
    }
}
