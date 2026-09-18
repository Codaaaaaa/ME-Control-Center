package io.github.codaaaaaa.mecc.core.resources;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Terminal search syntax (spec section 7.3). Whitespace-separated terms must all match; quote a phrase
 * to keep spaces ({@code "iron ingot"}).
 *
 * <ul>
 *   <li>{@code iron} – display name (current or English) or registry path contains it</li>
 *   <li>{@code minecraft:iron_ingot} – resource id contains it</li>
 *   <li>{@code @mekanism} – mod id starts with it, or mod name contains it</li>
 *   <li>{@code #forge:ingots} – a tag contains it</li>
 *   <li>{@code craftable:true}, {@code type:fluid}, {@code amount:<1000} (also {@code >}, {@code <=}, {@code >=},
 *       {@code =}; suffixes {@code k m b t})</li>
 * </ul>
 * Matching is case-insensitive.
 */
public final class ResourceSearch {
    private ResourceSearch() {
    }

    /** Pre-lowercased searchable fields of one resource. */
    public interface Row {
        String name();

        String englishName();

        /** Full resource id text, lowercase. */
        String id();

        String modId();

        String modName();

        List<String> tags();

        String type();

        boolean craftable();

        long amount();
    }

    public static Predicate<Row> parse(String query) {
        List<Predicate<Row>> terms = new ArrayList<>();
        for (String token : tokenize(query == null ? "" : query.toLowerCase(Locale.ROOT))) {
            terms.add(term(token));
        }
        return row -> {
            for (Predicate<Row> term : terms) {
                if (!term.test(row)) {
                    return false;
                }
            }
            return true;
        };
    }

    static List<String> tokenize(String query) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(c) && !quoted) {
                flush(tokens, current);
            } else {
                current.append(c);
            }
        }
        flush(tokens, current);
        return tokens.size() > 16 ? tokens.subList(0, 16) : tokens;
    }

    private static void flush(List<String> tokens, StringBuilder current) {
        if (!current.isEmpty()) {
            tokens.add(current.toString());
            current.setLength(0);
        }
    }

    private static Predicate<Row> term(String token) {
        if (token.startsWith("@") && token.length() > 1) {
            String mod = token.substring(1);
            return row -> row.modId().startsWith(mod) || row.modName().contains(mod);
        }
        if (token.startsWith("#") && token.length() > 1) {
            String tag = token.substring(1);
            return row -> {
                for (String candidate : row.tags()) {
                    if (candidate.contains(tag)) {
                        return true;
                    }
                }
                return false;
            };
        }
        if (token.startsWith("craftable:")) {
            boolean wanted = !token.substring("craftable:".length()).startsWith("f");
            return row -> row.craftable() == wanted;
        }
        if (token.startsWith("type:") && token.length() > 5) {
            String type = token.substring(5);
            return row -> type.equals("other")
                    ? !row.type().equals("item") && !row.type().equals("fluid")
                    : row.type().equals(type);
        }
        if (token.startsWith("amount:")) {
            Predicate<Row> amount = amountTerm(token.substring("amount:".length()));
            if (amount != null) {
                return amount;
            }
        }
        if (token.indexOf(':') > 0) {
            return row -> row.id().contains(token);
        }
        return row -> row.name().contains(token) || row.englishName().contains(token) || row.id().contains(token);
    }

    private static Predicate<Row> amountTerm(String expression) {
        String operator;
        if (expression.startsWith("<=") || expression.startsWith(">=")) {
            operator = expression.substring(0, 2);
        } else if (expression.startsWith("<") || expression.startsWith(">") || expression.startsWith("=")) {
            operator = expression.substring(0, 1);
        } else {
            operator = "=";
        }
        String number = expression.substring(operator.equals("=") && !expression.startsWith("=") ? 0 : operator.length());
        Long value = parseAmount(number);
        if (value == null) {
            return null;
        }
        return switch (operator) {
            case "<" -> row -> row.amount() < value;
            case "<=" -> row -> row.amount() <= value;
            case ">" -> row -> row.amount() > value;
            case ">=" -> row -> row.amount() >= value;
            default -> row -> row.amount() == value;
        };
    }

    static Long parseAmount(String text) {
        if (text.isEmpty()) {
            return null;
        }
        long multiplier = switch (text.charAt(text.length() - 1)) {
            case 'k' -> 1_000L;
            case 'm' -> 1_000_000L;
            case 'b', 'g' -> 1_000_000_000L;
            case 't' -> 1_000_000_000_000L;
            default -> 1L;
        };
        String digits = multiplier == 1 ? text : text.substring(0, text.length() - 1);
        try {
            double parsed = Double.parseDouble(digits);
            if (!Double.isFinite(parsed) || parsed < 0) {
                return null;
            }
            return (long) (parsed * multiplier);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
