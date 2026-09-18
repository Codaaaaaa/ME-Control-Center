package io.github.codaaaaaa.mecc.platform;

import io.github.codaaaaaa.mecc.core.patterns.PatternType;
import io.github.codaaaaaa.mecc.core.resources.ResourceDescriptor;
import io.github.codaaaaaa.mecc.core.resources.ResourceIndex;
import io.github.codaaaaaa.mecc.core.resources.ResourceText;
import java.util.List;
import java.util.Objects;

/**
 * Server-side recipe discovery for "Fill from Recipe" (spec sections 18 and 43, {@code RecipePlatform}), and the
 * list of every registered resource for picking ingredients that are not in storage.
 *
 * <p>Split like {@link StoragePlatform}: {@link #captureRecipes} only copies references on the server thread;
 * {@link #describe} converts them on an ME Control Center worker thread.
 */
public interface RecipePlatform {

    /** Copies references to every recipe that fits a pattern type. Cheap: no conversion happens here. */
    @ServerThreadOnly
    RecipeCapture captureRecipes();

    /** Converts a capture. Must <em>not</em> be called on the server thread; reads only immutable recipe data. */
    RecipeBook describe(RecipeCapture capture);

    /** Increments whenever recipes may have changed (data pack reloads). Thread-safe. */
    int recipesVersion();

    /** Opaque result of {@link #captureRecipes}. Safe to pass to another thread. */
    interface RecipeCapture {
        int size();
    }

    /**
     * A game recipe in pattern terms.
     *
     * @param slots      accepted resources per input slot, in pattern slot order; an empty list is an empty crafting cell
     * @param amounts    raw amount each slot consumes, parallel to {@code slots} (1 for crafting-type recipes)
     * @param output     what one run produces; for processing recipes the primary output
     * @param byproducts further guaranteed outputs of processing recipes, in recipe order
     * @param category   the machine or recipe type, e.g. {@code gtceu:assembler} or {@code minecraft:smelting}
     * @param complete   {@code false} when the recipe may consume more than listed (e.g. fluids or energy of a
     *                   machine type ME Control Center reads only generically)
     */
    record PatternRecipe(String id, PatternType type, List<List<ResourceDescriptor>> slots, List<Long> amounts,
                         ResourceDescriptor output, long outputAmount, List<Byproduct> byproducts, Category category,
                         boolean complete) {
        public PatternRecipe {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(output, "output");
            slots = slots.stream().map(List::copyOf).toList();
            amounts = amounts == null ? slots.stream().map(slot -> 1L).toList() : List.copyOf(amounts);
            if (amounts.size() != slots.size()) {
                throw new IllegalArgumentException("amounts must match slots");
            }
            byproducts = byproducts == null ? List.of() : List.copyOf(byproducts);
        }

        /** A crafting-table-like recipe: one item per slot, one output. */
        public PatternRecipe(String id, PatternType type, List<List<ResourceDescriptor>> slots, ResourceDescriptor output,
                             long outputAmount) {
            this(id, type, slots, null, output, outputAmount, List.of(), null, true);
        }
    }

    record Byproduct(ResourceDescriptor resource, long amount) {
        public Byproduct {
            Objects.requireNonNull(resource, "resource");
        }
    }

    /**
     * @param id   recipe type id, e.g. {@code gtceu:assembler}
     * @param name display name as the game composes it, or {@code null} to show the id
     */
    record Category(String id, ResourceText name) {
        public Category {
            Objects.requireNonNull(id, "id");
        }
    }

    /**
     * @param recipes  recipes of every supported pattern type
     * @param registry every registered item and fluid, with amount 0
     */
    record RecipeBook(List<PatternRecipe> recipes, ResourceIndex registry) {
        public RecipeBook {
            recipes = List.copyOf(recipes);
            Objects.requireNonNull(registry, "registry");
        }
    }
}
