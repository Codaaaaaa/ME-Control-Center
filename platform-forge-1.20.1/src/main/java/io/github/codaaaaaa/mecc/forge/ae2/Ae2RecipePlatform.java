package io.github.codaaaaaa.mecc.forge.ae2;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import io.github.codaaaaaa.mecc.core.patterns.PatternType;
import io.github.codaaaaaa.mecc.core.resources.ResourceDescriptor;
import io.github.codaaaaaa.mecc.core.resources.ResourceIndex;
import io.github.codaaaaaa.mecc.core.resources.ResourceText;
import io.github.codaaaaaa.mecc.platform.RecipePlatform;
import io.github.codaaaaaa.mecc.platform.ServerThreadOnly;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.crafting.IShapedRecipe;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side recipes for "Fill from Recipe" (spec section 18): straight from the server's recipe manager, without
 * JEI. Crafting, smithing-transform, and stonecutter recipes fill their editors; every other recipe type is offered
 * for processing patterns, read in the priority order of spec section 18.2:
 * <ol>
 *   <li>specific adapters for machines whose recipes the generic view misses (GregTech CEu: fluids, amounts);</li>
 *   <li>generic introspection: item ingredients and result, marked incomplete because a machine may also need
 *       fluids the generic recipe interface does not expose.</li>
 * </ol>
 * Recipes whose inputs cannot be listed (special recipes such as fireworks) are left out; they can still be
 * entered by hand.
 */
public final class Ae2RecipePlatform implements RecipePlatform {
    private static final Logger LOGGER = LoggerFactory.getLogger(Ae2RecipePlatform.class);
    /** Accepted items listed per slot; a tag with hundreds of members is summarized by its first few. */
    private static final int MAX_OPTIONS = 16;
    /** SmithingTransformRecipe keeps its ingredients in fields without accessors: template, base, addition. */
    private static final List<Field> SMITHING_FIELDS = ingredientFields(SmithingTransformRecipe.class);
    /** Names for the common vanilla machine recipe types; others show their id. */
    private static final Map<String, String> CATEGORY_NAMES = Map.of(
            "minecraft:smelting", "block.minecraft.furnace",
            "minecraft:blasting", "block.minecraft.blast_furnace",
            "minecraft:smoking", "block.minecraft.smoker",
            "minecraft:campfire_cooking", "block.minecraft.campfire",
            "ae2:inscriber", "block.ae2.inscriber");

    private final GtceuRecipes gtceu = GtceuRecipes.load();

    private final MinecraftServer server;
    private final Ae2StoragePlatform storage;

    public Ae2RecipePlatform(MinecraftServer server, Ae2StoragePlatform storage) {
        this.server = server;
        this.storage = storage;
    }

    private record Capture(List<Recipe<?>> recipes, RegistryAccess registries) implements RecipeCapture {
        @Override
        public int size() {
            return recipes.size();
        }
    }

    @Override
    @ServerThreadOnly
    public RecipeCapture captureRecipes() {
        Ae2Support.requireServerThread(server, "RecipePlatform.captureRecipes()");
        RecipeManager manager = server.getRecipeManager();
        return new Capture(new ArrayList<>(manager.getRecipes()), server.registryAccess());
    }

    @Override
    public int recipesVersion() {
        // Recipes and tags are reloaded together, with data packs.
        return storage.tagsVersion();
    }

    @Override
    public RecipeBook describe(RecipeCapture capture) {
        Capture source = (Capture) capture;
        List<PatternRecipe> recipes = new ArrayList<>(source.recipes().size());
        int skipped = 0;
        for (Recipe<?> recipe : source.recipes()) {
            try {
                PatternRecipe converted = convert(recipe, source.registries());
                if (converted != null) {
                    recipes.add(converted);
                } else {
                    skipped++;
                }
            } catch (RuntimeException | ReflectiveOperationException | LinkageError e) {
                skipped++;
                LOGGER.debug("Could not read recipe {}", recipe.getId(), e);
            }
        }
        LOGGER.debug("ME Control Center read {} pattern recipes ({} not listable)", recipes.size(), skipped);
        return new RecipeBook(recipes, registry());
    }

    private PatternRecipe convert(Recipe<?> recipe, RegistryAccess registries) throws ReflectiveOperationException {
        if (gtceu != null && gtceu.supports(recipe)) {
            return gtceu.convert(recipe, storage::describe);
        }
        ItemStack result = recipe.getResultItem(registries);
        if (result == null || result.isEmpty()) {
            return null;
        }
        String id = recipe.getId().toString();
        ResourceDescriptor output = storage.describe(AEItemKey.of(result));
        if (recipe instanceof CraftingRecipe crafting) {
            List<List<ResourceDescriptor>> slots = craftingSlots(crafting);
            return slots == null ? null : new PatternRecipe(id, PatternType.CRAFTING, slots, output, result.getCount());
        }
        if (recipe instanceof SmithingTransformRecipe smithing) {
            List<List<ResourceDescriptor>> slots = smithingSlots(smithing);
            return slots == null ? null : new PatternRecipe(id, PatternType.SMITHING, slots, output, result.getCount());
        }
        if (recipe instanceof StonecutterRecipe stonecutter) {
            NonNullList<Ingredient> ingredients = stonecutter.getIngredients();
            List<ResourceDescriptor> input = ingredients.isEmpty() ? List.of() : options(ingredients.get(0));
            return input.isEmpty() ? null
                    : new PatternRecipe(id, PatternType.STONECUTTING, List.of(input), output, result.getCount());
        }
        return processing(recipe, id, output, result.getCount());
    }

    /** Any other recipe type, read generically: its item ingredients and its result. */
    private PatternRecipe processing(Recipe<?> recipe, String id, ResourceDescriptor output, long outputAmount) {
        if (recipe.isSpecial()) {
            return null;
        }
        List<List<ResourceDescriptor>> slots = new ArrayList<>();
        List<Long> amounts = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            List<ResourceDescriptor> options = options(ingredient);
            if (options.isEmpty()) {
                return null;
            }
            ItemStack[] stacks = ingredient.getItems();
            slots.add(options);
            amounts.add((long) Math.max(1, stacks.length > 0 ? stacks[0].getCount() : 1));
        }
        if (slots.isEmpty()) {
            return null;
        }
        ResourceLocation type = ForgeRegistries.RECIPE_TYPES.getKey(recipe.getType());
        String typeId = type == null ? "unknown:unknown" : type.toString();
        String nameKey = CATEGORY_NAMES.get(typeId);
        Category category = new Category(typeId, nameKey == null ? null : ResourceText.translatable(nameKey, List.of()));
        return new PatternRecipe(id, PatternType.PROCESSING, slots, amounts, output, outputAmount, List.of(), category,
                false);
    }

    /** Nine cells, row by row; shaped recipes keep their layout in the top-left corner like the crafting table. */
    private List<List<ResourceDescriptor>> craftingSlots(CraftingRecipe recipe) {
        if (recipe.isSpecial()) {
            return null;
        }
        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty() || ingredients.size() > 9) {
            return null;
        }
        List<List<ResourceDescriptor>> slots = new ArrayList<>(Collections.nCopies(9, List.of()));
        boolean shaped = recipe instanceof IShapedRecipe<?>;
        int width = recipe instanceof IShapedRecipe<?> shapedRecipe ? shapedRecipe.getRecipeWidth() : 3;
        if (width < 1 || width > 3) {
            return null;
        }
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ingredient = ingredients.get(i);
            if (shaped && i / width > 2) {
                return null;
            }
            int cell = shaped ? (i / width) * 3 + i % width : i;
            if (ingredient.isEmpty()) {
                continue;
            }
            List<ResourceDescriptor> options = options(ingredient);
            if (options.isEmpty()) {
                return null; // e.g. an empty tag: the recipe cannot be made
            }
            slots.set(cell, options);
        }
        return slots;
    }

    private List<List<ResourceDescriptor>> smithingSlots(SmithingTransformRecipe recipe) {
        if (SMITHING_FIELDS.size() != 3) {
            return null;
        }
        List<List<ResourceDescriptor>> slots = new ArrayList<>(3);
        for (Field field : SMITHING_FIELDS) {
            Ingredient ingredient;
            try {
                ingredient = (Ingredient) field.get(recipe);
            } catch (IllegalAccessException e) {
                return null;
            }
            List<ResourceDescriptor> options = ingredient == null ? List.of() : options(ingredient);
            if (options.isEmpty()) {
                return null;
            }
            slots.add(options);
        }
        return slots;
    }

    private List<ResourceDescriptor> options(Ingredient ingredient) {
        Set<AEKey> keys = new LinkedHashSet<>();
        for (ItemStack stack : ingredient.getItems()) {
            AEItemKey key = AEItemKey.of(stack);
            if (key != null) {
                keys.add(key);
                if (keys.size() >= MAX_OPTIONS) {
                    break;
                }
            }
        }
        return keys.stream().map(storage::describe).toList();
    }

    private static List<Field> ingredientFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        try {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() == Ingredient.class && !Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    fields.add(field);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Smithing recipes cannot be listed for Fill from Recipe: {}", e.toString());
            return List.of();
        }
        return fields;
    }

    /** Every registered item and source fluid, so patterns can name resources the network has never seen. */
    private ResourceIndex registry() {
        List<ResourceDescriptor> descriptors = new ArrayList<>();
        for (Item item : ForgeRegistries.ITEMS.getValues()) {
            if (item != Items.AIR) {
                add(descriptors, AEItemKey.of(item));
            }
        }
        for (Fluid fluid : ForgeRegistries.FLUIDS.getValues()) {
            if (fluid != Fluids.EMPTY && fluid.isSource(fluid.defaultFluidState())) {
                add(descriptors, AEFluidKey.of(fluid));
            }
        }
        int size = descriptors.size();
        return new ResourceIndex(Instant.now(), descriptors.toArray(new ResourceDescriptor[0]), new long[size],
                new boolean[size], filled(size));
    }

    private void add(List<ResourceDescriptor> descriptors, AEKey key) {
        try {
            if (key != null) {
                descriptors.add(storage.describe(key));
            }
        } catch (RuntimeException e) {
            LOGGER.debug("Could not describe {}", key, e);
        }
    }

    private static long[] filled(int size) {
        long[] values = new long[size];
        java.util.Arrays.fill(values, ResourceIndex.NOT_CRAFTING);
        return values;
    }
}
