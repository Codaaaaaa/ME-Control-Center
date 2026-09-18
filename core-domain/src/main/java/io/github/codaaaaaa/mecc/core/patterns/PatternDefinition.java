package io.github.codaaaaaa.mecc.core.patterns;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A version-independent pattern as the browser authors it (spec section 13.1). It never contains platform
 * objects; the platform adapter turns it into a real encoded pattern.
 *
 * <p>Inputs are slots, and an empty slot is {@code null}:
 * <ul>
 *   <li>{@code CRAFTING}: 9 slots, row by row, each an item with amount 1;</li>
 *   <li>{@code SMITHING}: template, base, addition;</li>
 *   <li>{@code STONECUTTING}: the single input, the output selected by {@code recipeId};</li>
 *   <li>{@code PROCESSING}: any number of inputs, any resource type the platform supports.</li>
 * </ul>
 * Outputs are authored only for processing patterns, where the first one is the primary output; for the
 * other types the game recipe determines them and authored outputs are ignored.
 *
 * @param substitutes      crafting-type patterns may use equivalent items
 * @param fluidSubstitutes crafting patterns may use fluids instead of their containers
 * @param recipeId         game recipe to use, e.g. {@code minecraft:oak_planks}; required for stonecutting,
 *                         a hint for the other recipe-driven types, {@code null} otherwise
 */
public record PatternDefinition(
        PatternType type,
        List<PatternStack> inputs,
        List<PatternStack> outputs,
        boolean substitutes,
        boolean fluidSubstitutes,
        String recipeId) {

    public PatternDefinition {
        Objects.requireNonNull(type, "type");
        inputs = frozen(inputs);
        outputs = frozen(outputs);
        recipeId = recipeId == null || recipeId.isBlank() ? null : recipeId.strip();
    }

    /** Unmodifiable copy that, unlike {@link List#copyOf}, keeps {@code null} slots. */
    private static List<PatternStack> frozen(List<PatternStack> stacks) {
        return stacks == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(stacks));
    }

    /** Non-empty inputs, in slot order. */
    public List<PatternStack> presentInputs() {
        return inputs.stream().filter(Objects::nonNull).toList();
    }

    /** Non-empty outputs, in slot order. */
    public List<PatternStack> presentOutputs() {
        return outputs.stream().filter(Objects::nonNull).toList();
    }
}
