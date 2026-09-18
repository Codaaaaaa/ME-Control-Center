package io.github.codaaaaaa.mecc.core.patterns;

/** Pattern kinds Pattern Studio can author (spec section 13). Stored by name: never rename. */
public enum PatternType {
    /** A 3x3 crafting-table recipe. */
    CRAFTING,
    /** Arbitrary inputs turned into outputs by a machine outside the ME system. */
    PROCESSING,
    /** A smithing-table recipe: template, base, addition. */
    SMITHING,
    /** A stonecutter recipe: one input, the output chosen by recipe. */
    STONECUTTING;

    /** Number of input slots, or {@code -1} when variable (processing). */
    public int fixedInputSlots() {
        return switch (this) {
            case CRAFTING -> 9;
            case SMITHING -> 3;
            case STONECUTTING -> 1;
            case PROCESSING -> -1;
        };
    }

    /** Whether the outputs come from a game recipe rather than from the author. */
    public boolean recipeDriven() {
        return this != PROCESSING;
    }
}
