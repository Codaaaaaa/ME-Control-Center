package io.github.codaaaaaa.mecc.core.resources;

import java.util.List;
import java.util.Objects;

/**
 * A display name in the shape the game builds it: literal text, or a translation key with arguments that
 * are themselves names (spec section 40).
 *
 * <p>Carrying the whole structure rather than a single translation key is what makes composed names come
 * out right. A mod that names its materials through one shared key - {@code "%s Dust"} with the material
 * as the argument - is otherwise indistinguishable from an item actually called "Dust".
 *
 * @param literal        literal text, or {@code null} when this is a translation
 * @param key            translation key, or {@code null} when this is literal text
 * @param args           arguments substituted into the translation, in order
 * @param extra          parts appended after this one, each inheriting this part's style
 * @param color          {@code #rrggbb}, or {@code null} to inherit
 * @param bold           {@code null} to inherit
 * @param italic         {@code null} to inherit
 * @param underlined     {@code null} to inherit
 * @param strikethrough  {@code null} to inherit
 */
public record ResourceText(
        String literal,
        String key,
        List<ResourceText> args,
        List<ResourceText> extra,
        String color,
        Boolean bold,
        Boolean italic,
        Boolean underlined,
        Boolean strikethrough) {

    /** Depth and size limits, so a hostile or merely odd name cannot blow up a snapshot. */
    public static final int MAX_DEPTH = 8;
    public static final int MAX_PARTS = 64;
    public static final int MAX_LITERAL_LENGTH = 256;

    public ResourceText {
        args = args == null ? List.of() : List.copyOf(args);
        extra = extra == null ? List.of() : List.copyOf(extra);
    }

    public static ResourceText literal(String text) {
        return new ResourceText(Objects.requireNonNull(text, "text"), null, null, null, null, null, null, null, null);
    }

    public static ResourceText translatable(String key, List<ResourceText> args) {
        return new ResourceText(null, Objects.requireNonNull(key, "key"), args, null, null, null, null, null, null);
    }

    /** This part with the given style, keeping its content and parts. */
    public ResourceText styled(String color, Boolean bold, Boolean italic, Boolean underlined, Boolean strikethrough) {
        return new ResourceText(literal, key, args, extra, color, bold, italic, underlined, strikethrough);
    }

    /** This part followed by {@code parts}, which inherit its style. */
    public ResourceText withExtra(List<ResourceText> parts) {
        return new ResourceText(literal, key, args, parts, color, bold, italic, underlined, strikethrough);
    }
}
