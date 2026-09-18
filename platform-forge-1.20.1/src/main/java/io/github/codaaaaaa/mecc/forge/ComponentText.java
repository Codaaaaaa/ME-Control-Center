package io.github.codaaaaaa.mecc.forge;

import io.github.codaaaaaa.mecc.core.resources.ResourceText;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.LiteralContents;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Converts a Minecraft {@link Component} into the platform-independent {@link ResourceText}.
 *
 * <p>The structure is kept rather than flattened to a string, because a name is only resolvable in the
 * browser's language once its translation keys and arguments survive the trip. Styles are resolved against
 * the enclosing style here, where the game's own inheritance rules are available, so what leaves this
 * class needs no further interpretation.
 *
 * <p>Reads nothing but the component itself; safe off the server thread.
 */
public final class ComponentText {
    private ComponentText() {
    }

    /** @return the converted name, or {@code null} for a component with nothing to show */
    public static ResourceText of(Component component) {
        Counter counter = new Counter();
        ResourceText text = convert(component, Style.EMPTY, 0, counter);
        return text;
    }

    private static final class Counter {
        int parts;
    }

    private static ResourceText convert(Component component, Style inherited, int depth, Counter counter) {
        if (component == null || depth > ResourceText.MAX_DEPTH || ++counter.parts > ResourceText.MAX_PARTS) {
            return null;
        }
        Style style = component.getStyle().applyTo(inherited);
        ResourceText text = content(component, style, depth, counter);
        if (text == null) {
            return null;
        }
        text = applyStyle(text, style, inherited);

        List<ResourceText> siblings = new ArrayList<>();
        for (Component sibling : component.getSiblings()) {
            ResourceText converted = convert(sibling, style, depth + 1, counter);
            if (converted != null) {
                siblings.add(converted);
            }
        }
        return siblings.isEmpty() ? text : text.withExtra(siblings);
    }

    private static ResourceText content(Component component, Style style, int depth, Counter counter) {
        if (component.getContents() instanceof LiteralContents literal) {
            return literal.text().isEmpty() ? null : ResourceText.literal(clamp(literal.text()));
        }
        if (component.getContents() instanceof TranslatableContents translatable) {
            List<ResourceText> args = new ArrayList<>();
            for (Object argument : translatable.getArgs()) {
                ResourceText converted = argument instanceof Component nested
                        ? convert(nested, style, depth + 1, counter)
                        : ResourceText.literal(clamp(String.valueOf(argument)));
                args.add(converted != null ? converted : ResourceText.literal(""));
            }
            return ResourceText.translatable(translatable.getKey(), args);
        }
        // Scores, selectors, keybinds and NBT: only their rendered form means anything, and it already
        // includes the siblings, so this node stands for the whole component.
        String rendered = component.getString();
        return rendered.isEmpty() ? null : ResourceText.literal(clamp(rendered));
    }

    /** Records only what this component changes, so the receiving side can keep inheriting the rest. */
    private static ResourceText applyStyle(ResourceText text, Style style, Style inherited) {
        TextColor color = style.getColor();
        String hex = color == null || color.equals(inherited.getColor())
                ? null
                : String.format(Locale.ROOT, "#%06X", color.getValue() & 0xFFFFFF);
        Boolean bold = style.isBold() == inherited.isBold() ? null : style.isBold();
        Boolean italic = style.isItalic() == inherited.isItalic() ? null : style.isItalic();
        Boolean underlined = style.isUnderlined() == inherited.isUnderlined() ? null : style.isUnderlined();
        Boolean struck = style.isStrikethrough() == inherited.isStrikethrough() ? null : style.isStrikethrough();
        if (hex == null && bold == null && italic == null && underlined == null && struck == null) {
            return text;
        }
        return text.styled(hex, bold, italic, underlined, struck);
    }

    private static String clamp(String text) {
        return text.length() > ResourceText.MAX_LITERAL_LENGTH
                ? text.substring(0, ResourceText.MAX_LITERAL_LENGTH)
                : text;
    }
}
