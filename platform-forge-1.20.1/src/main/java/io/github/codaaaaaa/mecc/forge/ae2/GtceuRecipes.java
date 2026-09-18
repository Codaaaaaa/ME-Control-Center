package io.github.codaaaaaa.mecc.forge.ae2;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import io.github.codaaaaaa.mecc.core.patterns.PatternType;
import io.github.codaaaaaa.mecc.core.resources.ResourceDescriptor;
import io.github.codaaaaaa.mecc.core.resources.ResourceText;
import io.github.codaaaaaa.mecc.platform.RecipePlatform.Byproduct;
import io.github.codaaaaaa.mecc.platform.RecipePlatform.Category;
import io.github.codaaaaaa.mecc.platform.RecipePlatform.PatternRecipe;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.material.Fluid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads GregTech CEu Modern machine recipes (spec section 18.3) as processing recipes: consumed item and fluid
 * inputs with their exact amounts, and the guaranteed outputs. Programmed circuits and other inputs that are not
 * consumed are left out, as are chanced outputs; energy is supplied by the machine, not the pattern.
 *
 * <p>GTCEu is optional, so it is read through reflection; if its classes are missing or have changed shape this
 * reader disables itself and GTCEu recipes are read generically instead. Tested with GTCEu 1.4.x.
 */
final class GtceuRecipes {
    private static final Logger LOGGER = LoggerFactory.getLogger(GtceuRecipes.class);
    private static final int MAX_OPTIONS = 16;
    /** Capabilities whose absence from a pattern is expected: the machine provides them. */
    private static final Set<String> MACHINE_PROVIDED = Set.of("eu", "cwu", "su");

    private final Class<?> recipeClass;
    private final Field inputs;
    private final Field outputs;
    private final Field recipeType;
    private final Field typeName;
    private final Field capabilityName;
    private final Field content;
    private final Field chance;
    private final Field maxChance;
    private final Class<?> fluidIngredient;
    private final Method fluidStacks;
    private final Method fluidAmount;
    private final Method stackFluid;
    private final Method stackTag;

    private GtceuRecipes(ClassLoader loader) throws ReflectiveOperationException {
        recipeClass = Class.forName("com.gregtechceu.gtceu.api.recipe.GTRecipe", false, loader);
        inputs = recipeClass.getField("inputs");
        outputs = recipeClass.getField("outputs");
        recipeType = recipeClass.getField("recipeType");
        typeName = Class.forName("com.gregtechceu.gtceu.api.recipe.GTRecipeType", false, loader).getField("registryName");
        capabilityName = Class.forName("com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability", false, loader)
                .getField("name");
        Class<?> contentClass = Class.forName("com.gregtechceu.gtceu.api.recipe.content.Content", false, loader);
        content = contentClass.getField("content");
        chance = contentClass.getField("chance");
        maxChance = contentClass.getField("maxChance");
        fluidIngredient = Class.forName("com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient", false, loader);
        fluidStacks = fluidIngredient.getMethod("getStacks");
        fluidAmount = fluidIngredient.getMethod("getAmount");
        Class<?> fluidStack = fluidStacks.getReturnType().getComponentType();
        stackFluid = fluidStack.getMethod("getFluid");
        stackTag = fluidStack.getMethod("getTag");
    }

    /** The reader, or {@code null} when GTCEu is not installed or not readable. */
    static GtceuRecipes load() {
        try {
            GtceuRecipes reader = new GtceuRecipes(Ae2RecipePlatform.class.getClassLoader());
            LOGGER.info("ME Control Center reads GregTech CEu machine recipes for Pattern Studio");
            return reader;
        } catch (ClassNotFoundException e) {
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("GregTech CEu is installed but its recipes cannot be read ({}); they are read generically", e.toString());
            return null;
        }
    }

    boolean supports(Recipe<?> recipe) {
        return recipeClass.isInstance(recipe);
    }

    /** The recipe as a processing recipe, or {@code null} when it has no guaranteed output. */
    PatternRecipe convert(Recipe<?> recipe, Function<AEKey, ResourceDescriptor> describe) throws ReflectiveOperationException {
        List<List<ResourceDescriptor>> slots = new ArrayList<>();
        List<Long> amounts = new ArrayList<>();
        boolean complete = true;
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) inputs.get(recipe)).entrySet()) {
            String capability = String.valueOf(capabilityName.get(entry.getKey()));
            for (Object item : (List<?>) entry.getValue()) {
                Object value = content.get(item);
                // Inputs that are not consumed stay in the machine (molds, lenses) and are left out, except the
                // programmed circuit: pattern buffers take it from the pattern to select the recipe.
                if (chance.getInt(item) == 0 && !isCircuit(value)) {
                    continue;
                }
                Stacked stacked = stacked(capability, value, describe);
                if (stacked == null) {
                    complete &= MACHINE_PROVIDED.contains(capability);
                    continue;
                }
                slots.add(stacked.options());
                amounts.add(stacked.amount());
            }
        }

        List<Byproduct> produced = new ArrayList<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) outputs.get(recipe)).entrySet()) {
            String capability = String.valueOf(capabilityName.get(entry.getKey()));
            for (Object item : (List<?>) entry.getValue()) {
                if (chance.getInt(item) < maxChance.getInt(item)) {
                    continue; // chanced outputs cannot be promised by a pattern
                }
                Stacked stacked = stacked(capability, content.get(item), describe);
                if (stacked != null && !stacked.options().isEmpty()) {
                    produced.add(new Byproduct(stacked.options().get(0), stacked.amount()));
                }
            }
        }
        if (slots.isEmpty() || produced.isEmpty()) {
            return null;
        }
        // Items before fluids, as GTCEu lists them in its own UI.
        produced.sort((a, b) -> Boolean.compare(!a.resource().id().type().equals("item"), !b.resource().id().type().equals("item")));
        Byproduct primary = produced.get(0);
        return new PatternRecipe(recipe.getId().toString(), PatternType.PROCESSING, slots, amounts, primary.resource(),
                primary.amount(), produced.subList(1, produced.size()), category(recipe), complete);
    }

    private record Stacked(List<ResourceDescriptor> options, long amount) {
    }

    private Stacked stacked(String capability, Object value, Function<AEKey, ResourceDescriptor> describe)
            throws ReflectiveOperationException {
        if (capability.equals("item") && value instanceof Ingredient ingredient) {
            ItemStack[] stacks = ingredient.getItems();
            if (stacks.length == 0) {
                return null;
            }
            Set<AEKey> keys = new LinkedHashSet<>();
            for (ItemStack stack : stacks) {
                AEItemKey key = AEItemKey.of(stack);
                if (key != null && keys.size() < MAX_OPTIONS) {
                    keys.add(key);
                }
            }
            long amount = itemAmount(ingredient, stacks[0]);
            return keys.isEmpty() || amount < 1 ? null : new Stacked(keys.stream().map(describe).toList(), amount);
        }
        if (capability.equals("fluid") && fluidIngredient.isInstance(value)) {
            Object[] stacks = (Object[]) fluidStacks.invoke(value);
            Set<AEKey> keys = new LinkedHashSet<>();
            for (Object stack : stacks) {
                Fluid fluid = (Fluid) stackFluid.invoke(stack);
                AEFluidKey key = fluid == null ? null : AEFluidKey.of(fluid, (CompoundTag) stackTag.invoke(stack));
                if (key != null && keys.size() < MAX_OPTIONS) {
                    keys.add(key);
                }
            }
            long amount = ((Number) fluidAmount.invoke(value)).longValue();
            return keys.isEmpty() || amount < 1 ? null : new Stacked(keys.stream().map(describe).toList(), amount);
        }
        return null;
    }

    /** GTCEu's programmed circuit ingredient (one configured circuit), by name so no GTCEu class is needed. */
    private static boolean isCircuit(Object value) {
        return value != null && value.getClass().getSimpleName().equals("IntCircuitIngredient");
    }

    /** Sized ingredients carry their amount; others use the count of their first item. */
    private static long itemAmount(Ingredient ingredient, ItemStack first) {
        try {
            Method amount = ingredient.getClass().getMethod("getAmount");
            if (amount.getReturnType() == int.class || amount.getReturnType() == long.class) {
                return ((Number) amount.invoke(ingredient)).longValue();
            }
        } catch (ReflectiveOperationException e) {
            // Not a sized ingredient.
        }
        return Math.max(1, first.getCount());
    }

    private Category category(Recipe<?> recipe) throws ReflectiveOperationException {
        Object type = recipeType.get(recipe);
        ResourceLocation id = type == null ? null : (ResourceLocation) typeName.get(type);
        if (id == null) {
            return null;
        }
        return new Category(id.toString(), ResourceText.translatable(id.toLanguageKey(), List.of()));
    }
}
