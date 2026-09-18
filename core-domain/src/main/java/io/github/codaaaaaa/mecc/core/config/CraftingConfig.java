package io.github.codaaaaaa.mecc.core.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Remote autocrafting settings ({@code [crafting]} section).
 *
 * @param maxCraftAmount            largest amount one request may ask for (spec section 46, {@code max_craft_amount})
 * @param calculationTimeoutSeconds a crafting calculation still running after this long is abandoned
 */
public record CraftingConfig(long maxCraftAmount, int calculationTimeoutSeconds) {
    public static final long DEFAULT_MAX_CRAFT_AMOUNT = 1_000_000_000L;
    public static final int DEFAULT_CALCULATION_TIMEOUT_SECONDS = 60;

    public static CraftingConfig defaults() {
        return new CraftingConfig(DEFAULT_MAX_CRAFT_AMOUNT, DEFAULT_CALCULATION_TIMEOUT_SECONDS);
    }

    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (maxCraftAmount < 1) {
            problems.add("crafting.max_craft_amount must be at least 1, got " + maxCraftAmount);
        }
        if (calculationTimeoutSeconds < 5 || calculationTimeoutSeconds > 600) {
            problems.add("crafting.calculation_timeout_seconds must be between 5 and 600, got " + calculationTimeoutSeconds);
        }
        return problems;
    }
}
