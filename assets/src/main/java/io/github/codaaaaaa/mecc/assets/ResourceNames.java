package io.github.codaaaaaa.mecc.assets;

import io.github.codaaaaaa.mecc.core.resources.NameSpan;
import io.github.codaaaaaa.mecc.core.resources.ResourceDescriptor;
import io.github.codaaaaaa.mecc.core.resources.ResourceText;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Display names from the best available source (spec section 40): the name the game composed, resolved in
 * the requested locale, then English, then a readable form of the registry path. Never shows a raw
 * translation key.
 *
 * <p>Translation arguments are substituted rather than removed, which is what a mod's shared
 * {@code "%s Dust"} key needs to come out as "Sodium Dust" instead of "Dust". Styling - both the component
 * styles the game uses and the legacy section-sign codes that end up inside translations - becomes spans the
 * terminal can render, so no section sign ever reaches the screen.
 */
public final class ResourceNames {
    public static final String FALLBACK_LOCALE = "en_us";

    /** Vanilla's legacy colour codes, in the order {@code \u00a70} to {@code \u00a7f}. */
    private static final String[] LEGACY_COLORS = {
            "#000000", "#0000AA", "#00AA00", "#00AAAA", "#AA0000", "#AA00AA", "#FFAA00", "#AAAAAA",
            "#555555", "#5555FF", "#55FF55", "#55FFFF", "#FF5555", "#FF55FF", "#FFFF55", "#FFFFFF"};
    private static final char SECTION = '\u00a7';

    private final LanguageTables languages;

    public ResourceNames(LanguageTables languages) {
        this.languages = languages;
    }

    /** Plain display name, for searching, sorting and anywhere styling cannot be shown. */
    public String displayName(ResourceDescriptor descriptor, String locale) {
        StringBuilder text = new StringBuilder();
        for (NameSpan span : spans(descriptor, locale)) {
            text.append(span.text());
        }
        return text.toString();
    }

    /** Display name split into styled runs; always at least one span, never empty text. */
    public List<NameSpan> spans(ResourceDescriptor descriptor, String locale) {
        List<NameSpan> spans = new ArrayList<>();
        if (descriptor.name() != null) {
            append(spans, descriptor.name(), Style.INHERIT, locale, 0);
        }
        List<NameSpan> merged = merge(spans);
        return merged.isEmpty() ? List.of(NameSpan.plain(fallbackName(descriptor, locale))) : merged;
    }

    /**
     * Any game text (e.g. a player-given CPU name) as plain text in {@code locale}.
     *
     * @return {@code null} when {@code text} is {@code null} or resolves to nothing
     */
    public String plainText(ResourceText text, String locale) {
        if (text == null) {
            return null;
        }
        List<NameSpan> spans = new ArrayList<>();
        append(spans, text, Style.INHERIT, locale, 0);
        StringBuilder plain = new StringBuilder();
        spans.forEach(span -> plain.append(span.text()));
        String result = plain.toString().strip();
        return result.isEmpty() ? null : result;
    }

    /** {@code tools/iron_ingot} becomes {@code Iron Ingot}. */
    public static String prettify(String path) {
        String last = path.substring(path.lastIndexOf('/') + 1);
        StringBuilder result = new StringBuilder();
        for (String word : last.split("[_\\-.]+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return result.isEmpty() ? path : result.toString();
    }

    private String fallbackName(ResourceDescriptor descriptor, String locale) {
        return translate(locale, descriptor.descriptionKey())
                .map(pattern -> pattern.replaceAll("%(\\d+\\$)?[sd]", "").strip())
                .filter(name -> !name.isEmpty())
                .orElseGet(() -> prettify(descriptor.id().path()));
    }

    // --- resolution --------------------------------------------------------------------------------

    /** A style with every property decided; {@code null} colour means the terminal's own text colour. */
    private record Style(String color, boolean bold, boolean italic, boolean underlined, boolean strikethrough) {
        static final Style INHERIT = new Style(null, false, false, false, false);

        Style with(ResourceText text) {
            return new Style(
                    text.color() != null ? text.color() : color,
                    text.bold() != null ? text.bold() : bold,
                    text.italic() != null ? text.italic() : italic,
                    text.underlined() != null ? text.underlined() : underlined,
                    text.strikethrough() != null ? text.strikethrough() : strikethrough);
        }

        NameSpan span(String text) {
            return new NameSpan(text, color, bold, italic, underlined, strikethrough);
        }
    }

    private void append(List<NameSpan> out, ResourceText text, Style inherited, String locale, int depth) {
        if (depth > ResourceText.MAX_DEPTH || out.size() >= ResourceText.MAX_PARTS) {
            return;
        }
        Style style = inherited.with(text);
        if (text.literal() != null) {
            appendLiteral(out, text.literal(), style);
        } else if (text.key() != null) {
            appendTranslation(out, text, style, locale, depth);
        }
        for (ResourceText part : text.extra()) {
            append(out, part, style, locale, depth + 1);
        }
    }

    private void appendTranslation(List<NameSpan> out, ResourceText text, Style style, String locale, int depth) {
        Optional<String> pattern = translate(locale, text.key());
        if (pattern.isEmpty()) {
            // An unknown key still has known arguments often enough to be worth showing: a composed name
            // whose prefix is untranslated reads better as "Sodium" than as its raw key.
            for (ResourceText argument : text.args()) {
                append(out, argument, style, locale, depth + 1);
            }
            return;
        }
        String format = pattern.get();
        List<ResourceText> args = text.args();
        StringBuilder run = new StringBuilder();
        int next = 0;
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            if (c != '%' || i + 1 >= format.length()) {
                run.append(c);
                continue;
            }
            char after = format.charAt(i + 1);
            if (after == '%') {
                run.append('%');
                i++;
                continue;
            }
            // Either %s / %d, or the indexed %<n>$s form.
            int cursor = i + 1;
            int index = -1;
            int digits = cursor;
            while (digits < format.length() && Character.isDigit(format.charAt(digits))) {
                digits++;
            }
            if (digits > cursor && digits < format.length() && format.charAt(digits) == '$') {
                index = Integer.parseInt(format.substring(cursor, digits)) - 1;
                cursor = digits + 1;
            }
            if (cursor >= format.length() || (format.charAt(cursor) != 's' && format.charAt(cursor) != 'd')) {
                run.append(c);
                continue;
            }
            if (index < 0) {
                index = next++;
            }
            appendLiteral(out, run.toString(), style);
            run.setLength(0);
            if (index >= 0 && index < args.size()) {
                append(out, args.get(index), style, locale, depth + 1);
            }
            i = cursor;
        }
        appendLiteral(out, run.toString(), style);
    }

    /**
     * Emits a literal run, turning any legacy section-sign codes it contains into spans of their own. Mods and
     * modpacks put these inside translations, where nothing else would ever strip them.
     */
    private static void appendLiteral(List<NameSpan> out, String literal, Style style) {
        if (literal.isEmpty() || out.size() >= ResourceText.MAX_PARTS) {
            return;
        }
        if (literal.indexOf(SECTION) < 0) {
            out.add(style.span(clamp(literal)));
            return;
        }
        Style current = style;
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if (c != SECTION || i + 1 >= literal.length()) {
                run.append(c);
                continue;
            }
            char code = Character.toLowerCase(literal.charAt(++i));
            Style updated = apply(current, code, style);
            if (updated == null) {
                // Not a formatting code after all: keep both characters as written.
                run.append(c).append(literal.charAt(i));
                continue;
            }
            if (!run.isEmpty()) {
                out.add(current.span(clamp(run.toString())));
                run.setLength(0);
                if (out.size() >= ResourceText.MAX_PARTS) {
                    return;
                }
            }
            current = updated;
        }
        if (!run.isEmpty()) {
            out.add(current.span(clamp(run.toString())));
        }
    }

    /** The style after a section-sign code, or {@code null} if the character is not one. */
    private static Style apply(Style current, char code, Style reset) {
        int color = Character.digit(code, 16);
        if (color >= 0) {
            // A colour code clears the other formatting, as it does in the game.
            return new Style(LEGACY_COLORS[color], false, false, false, false);
        }
        return switch (code) {
            case 'l' -> new Style(current.color(), true, current.italic(), current.underlined(), current.strikethrough());
            case 'o' -> new Style(current.color(), current.bold(), true, current.underlined(), current.strikethrough());
            case 'n' -> new Style(current.color(), current.bold(), current.italic(), true, current.strikethrough());
            case 'm' -> new Style(current.color(), current.bold(), current.italic(), current.underlined(), true);
            // Obfuscated text has no useful still form; it keeps the current style.
            case 'k' -> current;
            case 'r' -> reset;
            default -> null;
        };
    }

    private static String clamp(String text) {
        return text.length() > ResourceText.MAX_LITERAL_LENGTH ? text.substring(0, ResourceText.MAX_LITERAL_LENGTH) : text;
    }

    private static List<NameSpan> merge(List<NameSpan> spans) {
        List<NameSpan> merged = new ArrayList<>(spans.size());
        for (NameSpan span : spans) {
            if (span.text().isEmpty()) {
                continue;
            }
            int last = merged.size() - 1;
            if (last >= 0 && merged.get(last).sameStyle(span)) {
                NameSpan previous = merged.get(last);
                merged.set(last, new NameSpan(previous.text() + span.text(), previous.color(), previous.bold(),
                        previous.italic(), previous.underlined(), previous.strikethrough()));
            } else {
                merged.add(span);
            }
        }
        if (merged.size() == 1 && merged.get(0).text().isBlank()) {
            return List.of();
        }
        return merged;
    }

    private Optional<String> translate(String locale, String key) {
        if (key == null) {
            return Optional.empty();
        }
        Optional<String> translated = languages.translate(locale, key);
        return translated.isPresent() ? translated : languages.translate(FALLBACK_LOCALE, key);
    }
}
