package io.github.codaaaaaa.mecc.forge.ae2;

import appeng.api.config.Actionable;
import appeng.api.config.Settings;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageHelper;
import appeng.core.definitions.AEItems;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.crafting.pattern.AESmithingTablePattern;
import appeng.crafting.pattern.AEStonecuttingPattern;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.menu.AutoCraftingMenu;
import appeng.parts.AEBasePart;
import io.github.codaaaaaa.mecc.core.networks.BlockLocation;
import io.github.codaaaaaa.mecc.core.patterns.PatternDefinition;
import io.github.codaaaaaa.mecc.core.patterns.PatternIssue;
import io.github.codaaaaaa.mecc.core.patterns.PatternStack;
import io.github.codaaaaaa.mecc.core.patterns.PatternType;
import io.github.codaaaaaa.mecc.core.resources.ResourceId;
import io.github.codaaaaaa.mecc.core.resources.ResourceText;
import io.github.codaaaaaa.mecc.core.users.PlayerProfile;
import io.github.codaaaaaa.mecc.forge.ComponentText;
import io.github.codaaaaaa.mecc.platform.PatternPlatform;
import io.github.codaaaaaa.mecc.platform.ServerThreadOnly;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AE2 15 pattern encoding and pattern containers (spec sections 13-15): AE2's pattern providers and every other
 * {@link PatternContainer}, such as GregTech and GTL multiblock pattern buffers.
 *
 * <p>Encoding mirrors AE2's Pattern Encoding Terminal: the same recipe lookups and the same
 * {@link PatternDetailsHelper} encoders, so an encoded pattern is indistinguishable from one made in game.
 * The whole encode-and-deliver step runs inside one server-thread call, so no other game logic interleaves;
 * a Blank Pattern taken from storage is returned whenever a later step fails.
 *
 * <p>Pinned to AE2 {@value Ae2Integration#TESTED_VERSION}; provider internals used here are confined to this class.
 */
public final class Ae2PatternPlatform implements PatternPlatform {
    private static final Logger LOGGER = LoggerFactory.getLogger(Ae2PatternPlatform.class);

    private final MinecraftServer server;
    private final Ae2StoragePlatform storage;

    public Ae2PatternPlatform(MinecraftServer server, Ae2StoragePlatform storage) {
        this.server = server;
        this.storage = storage;
    }

    private static AEItemKey blankPattern() {
        return AEItemKey.of(AEItems.BLANK_PATTERN);
    }

    // --- providers ----------------------------------------------------------------------------------

    @Override
    @ServerThreadOnly
    public ProviderCapture captureProviders(String gridKey) {
        Ae2Support.requireServerThread(server, "PatternPlatform.captureProviders()");
        IGrid grid = Ae2Support.grid(gridKey);
        List<ProviderState> providers = new ArrayList<>();
        providers(grid).forEach((host, online) -> {
            try {
                providers.add(describe(host, online));
            } catch (RuntimeException e) {
                LOGGER.debug("Could not read pattern provider {}", host, e);
            }
        });
        long blanks = grid.getStorageService().getCachedInventory().get(blankPattern());
        return new ProviderCapture(Instant.now(), providers, blanks);
    }

    /**
     * Every pattern container of the grid - AE2 pattern providers and other mods' pattern buffers alike, exactly
     * what the Pattern Access Terminal lists - and whether it is online (powered, with a channel).
     */
    private static Map<PatternContainer, Boolean> providers(IGrid grid) {
        Map<PatternContainer, Boolean> containers = new LinkedHashMap<>();
        for (Class<?> machineClass : grid.getMachineClasses()) {
            if (!PatternContainer.class.isAssignableFrom(machineClass)) {
                continue;
            }
            var active = grid.getActiveMachines(machineClass);
            for (Object machine : grid.getMachines(machineClass)) {
                containers.put((PatternContainer) machine, active.contains(machine));
            }
        }
        return containers;
    }

    /** Online pattern containers of a grid, for the network status. */
    static int countOnlineProviders(IGrid grid) {
        int count = 0;
        for (Class<?> machineClass : grid.getMachineClasses()) {
            if (PatternContainer.class.isAssignableFrom(machineClass)) {
                count += grid.getActiveMachines(machineClass).size();
            }
        }
        return count;
    }

    private ProviderState describe(PatternContainer container, boolean online) {
        PatternContainers.Placement placement = PatternContainers.placement(container);
        Level level = placement == null ? null : placement.level();
        BlockLocation location = placement == null || level == null ? null
                : new BlockLocation(level.dimension().location().toString(), placement.pos().getX(), placement.pos().getY(),
                placement.pos().getZ());

        InternalInventory inventory = container.getTerminalPatternInventory();
        List<StoredPattern> patterns = new ArrayList<>();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                patterns.add(stored(slot, stack, level));
            }
        }

        AEItemKey kind = PatternContainers.kind(container, placement);
        // The group names the machine the container serves, like the Pattern Access Terminal. For AE2's providers
        // that means reading neighbouring blocks, which must never load a chunk.
        boolean safe = !(container instanceof PatternProviderLogicHost)
                || (level != null && neighboursLoaded(level, placement.pos()));
        PatternContainerGroup group = safe ? container.getTerminalGroup() : null;
        AEItemKey multiblock = PatternContainers.multiblock(container);
        AEItemKey machine = multiblock != null ? multiblock
                : group != null && group.icon() != null && !group.icon().equals(kind) ? group.icon() : null;
        String customName = PatternContainers.customName(container);
        ResourceText name = customName != null ? ResourceText.literal(customName)
                : group != null ? ComponentText.of(group.name())
                : kind != null ? ComponentText.of(kind.getDisplayName()) : null;
        AEItemKey icon = machine != null ? machine : kind;

        Integer priority = null;
        Boolean blocking = null;
        String lockMode = null;
        if (container instanceof PatternProviderLogicHost host) {
            PatternProviderLogic logic = host.getLogic();
            priority = logic.getPriority();
            blocking = logic.isBlocking();
            lockMode = logic.getConfigManager().getSetting(Settings.LOCK_CRAFTING_MODE).name();
        }
        return new ProviderState(providerId(container, placement), name, describeOrNull(icon), describeOrNull(kind),
                describeOrNull(machine), customName, PatternContainers.renamable(container), location, online,
                inventory.size(), priority, blocking, lockMode, container.isVisibleInTerminal(), patterns);
    }

    private io.github.codaaaaaa.mecc.core.resources.ResourceDescriptor describeOrNull(AEItemKey key) {
        return key == null ? null : storage.describe(key);
    }

    private static boolean neighboursLoaded(Level level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (!level.isLoaded(pos.relative(direction))) {
                return false;
            }
        }
        return true;
    }

    private StoredPattern stored(int slot, ItemStack stack, Level level) {
        IPatternDetails details = level == null ? null : PatternDetailsHelper.decodePattern(stack, level);
        if (details == null) {
            return new StoredPattern(slot, null, List.of(), List.of());
        }
        List<PatternAmount> inputs = new ArrayList<>();
        for (IPatternDetails.IInput input : details.getInputs()) {
            GenericStack[] candidates = input.getPossibleInputs();
            if (candidates.length > 0) {
                inputs.add(new PatternAmount(storage.describe(candidates[0].what()),
                        candidates[0].amount() * input.getMultiplier()));
            }
        }
        return new StoredPattern(slot, typeOf(details), amounts(details.getOutputs()), inputs);
    }

    private static PatternType typeOf(IPatternDetails details) {
        if (details instanceof AECraftingPattern) {
            return PatternType.CRAFTING;
        }
        if (details instanceof AEProcessingPattern) {
            return PatternType.PROCESSING;
        }
        if (details instanceof AESmithingTablePattern) {
            return PatternType.SMITHING;
        }
        if (details instanceof AEStonecuttingPattern) {
            return PatternType.STONECUTTING;
        }
        return null;
    }

    private List<PatternAmount> amounts(GenericStack[] stacks) {
        List<PatternAmount> amounts = new ArrayList<>();
        for (GenericStack stack : stacks) {
            if (stack != null) {
                amounts.add(new PatternAmount(storage.describe(stack.what()), stack.amount()));
            }
        }
        return amounts;
    }

    /** Position-based, so it survives restarts; parts on the same cable bus are told apart by side. */
    static String providerId(PatternContainer container, PatternContainers.Placement placement) {
        if (placement == null) {
            // Without a position the id only lasts as long as the object: better than nothing.
            return "o" + Integer.toHexString(System.identityHashCode(container));
        }
        Level level = placement.level();
        BlockPos pos = placement.pos();
        String key = (level == null ? "?" : level.dimension().location().toString()) + "@" + pos.getX() + "," + pos.getY()
                + "," + pos.getZ();
        if (container instanceof AEBasePart part && part.getSide() != null) {
            key += "#" + part.getSide().getName();
        }
        return "p" + Ae2Support.shortHash(key);
    }

    private static Optional<Map.Entry<PatternContainer, Boolean>> findProvider(IGrid grid, String providerId) {
        return providers(grid).entrySet().stream()
                .filter(entry -> providerId(entry.getKey(), PatternContainers.placement(entry.getKey())).equals(providerId))
                .findFirst();
    }

    @Override
    @ServerThreadOnly
    public RenameOutcome rename(String gridKey, String providerId, String name) {
        Ae2Support.requireServerThread(server, "PatternPlatform.rename()");
        PatternContainer container = findProvider(Ae2Support.grid(gridKey), providerId).map(Map.Entry::getKey).orElse(null);
        if (container == null) {
            return RenameOutcome.NOT_FOUND;
        }
        return PatternContainers.rename(container, name) ? RenameOutcome.RENAMED : RenameOutcome.NOT_RENAMABLE;
    }

    // --- building patterns --------------------------------------------------------------------------

    /** An encoded pattern, or the reasons it cannot be made. */
    private record Built(List<PatternIssue> issues, ItemStack encoded, IPatternDetails details, List<PatternAmount> outputs,
                         String recipeId) {
        static Built invalid(List<PatternIssue> issues) {
            return new Built(issues, ItemStack.EMPTY, null, List.of(), null);
        }
    }

    @Override
    @ServerThreadOnly
    public PatternCheck check(String gridKey, PatternDefinition definition) {
        Ae2Support.requireServerThread(server, "PatternPlatform.check()");
        IGrid grid = Ae2Support.grid(gridKey);
        Built built = build(grid, level(grid), definition);
        return new PatternCheck(built.issues(), built.outputs(), built.recipeId(),
                grid.getStorageService().getCachedInventory().get(blankPattern()));
    }

    private Level level(IGrid grid) {
        IGridNode pivot = grid.getPivot();
        Level level = pivot == null ? null : pivot.getLevel();
        return level != null ? level : server.overworld();
    }

    private Built build(IGrid grid, Level level, PatternDefinition definition) {
        List<PatternIssue> issues = new ArrayList<>();
        List<AEKey> keys = new ArrayList<>(definition.inputs().size());
        for (int i = 0; i < definition.inputs().size(); i++) {
            keys.add(resolve(grid, definition.inputs().get(i), "inputs[" + i + "]", issues));
        }
        List<AEKey> outputKeys = new ArrayList<>(definition.outputs().size());
        for (int i = 0; i < definition.outputs().size(); i++) {
            outputKeys.add(resolve(grid, definition.outputs().get(i), "outputs[" + i + "]", issues));
        }
        if (definition.type().recipeDriven()) {
            for (int i = 0; i < keys.size(); i++) {
                if (keys.get(i) != null && !(keys.get(i) instanceof AEItemKey)) {
                    issues.add(new PatternIssue(PatternIssue.NOT_AN_ITEM, "inputs[" + i + "]",
                            "Only items can be used in this pattern type"));
                }
            }
        }
        if (!issues.isEmpty()) {
            return Built.invalid(issues);
        }

        try {
            return switch (definition.type()) {
                case CRAFTING -> crafting(level, definition, keys);
                case PROCESSING -> processing(level, definition, keys, outputKeys);
                case SMITHING -> smithing(level, definition, keys);
                case STONECUTTING -> stonecutting(level, definition, keys);
            };
        } catch (RuntimeException e) {
            // AE2's encoders reject what they cannot represent with IllegalArgumentException and the like.
            LOGGER.debug("AE2 could not encode pattern {}", definition, e);
            return Built.invalid(List.of(new PatternIssue(PatternIssue.NOT_ENCODABLE, null,
                    "AE2 cannot encode this pattern")));
        }
    }

    private Built crafting(Level level, PatternDefinition definition, List<AEKey> keys) {
        ItemStack[] grid = new ItemStack[9];
        TransientCraftingContainer container = new TransientCraftingContainer(new AutoCraftingMenu(), 3, 3);
        for (int i = 0; i < 9; i++) {
            grid[i] = keys.get(i) instanceof AEItemKey item ? item.toStack(1) : ItemStack.EMPTY;
            container.setItem(i, grid[i]);
        }
        CraftingRecipe recipe = byId(level, definition.recipeId(), CraftingRecipe.class)
                .filter(candidate -> candidate.matches(container, level))
                .or(() -> level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, container, level))
                .orElse(null);
        ItemStack result = recipe == null ? ItemStack.EMPTY : recipe.assemble(container, level.registryAccess());
        if (result.isEmpty()) {
            return noRecipe("No crafting recipe matches this grid");
        }
        ItemStack encoded = PatternDetailsHelper.encodeCraftingPattern(recipe, grid, result, definition.substitutes(),
                definition.fluidSubstitutes());
        return finish(level, encoded, recipe);
    }

    private Built processing(Level level, PatternDefinition definition, List<AEKey> keys, List<AEKey> outputKeys) {
        GenericStack[] inputs = stacks(definition.inputs(), keys);
        GenericStack[] outputs = stacks(definition.outputs(), outputKeys);
        return finish(level, PatternDetailsHelper.encodeProcessingPattern(inputs, outputs), null);
    }

    private static GenericStack[] stacks(List<PatternStack> stacks, List<AEKey> keys) {
        GenericStack[] result = new GenericStack[stacks.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = stacks.get(i) == null ? null : new GenericStack(keys.get(i), stacks.get(i).amount());
        }
        return result;
    }

    private Built smithing(Level level, PatternDefinition definition, List<AEKey> keys) {
        AEItemKey template = (AEItemKey) keys.get(0);
        AEItemKey base = (AEItemKey) keys.get(1);
        AEItemKey addition = (AEItemKey) keys.get(2);
        SimpleContainer container = new SimpleContainer(template.toStack(), base.toStack(), addition.toStack());
        SmithingRecipe recipe = byId(level, definition.recipeId(), SmithingRecipe.class)
                .filter(candidate -> candidate.matches(container, level))
                .or(() -> level.getRecipeManager().getRecipeFor(RecipeType.SMITHING, container, level))
                .orElse(null);
        ItemStack result = recipe == null ? ItemStack.EMPTY : recipe.assemble(container, level.registryAccess());
        if (result.isEmpty()) {
            return noRecipe("No smithing recipe takes these items");
        }
        ItemStack encoded = PatternDetailsHelper.encodeSmithingTablePattern(recipe, template, base, addition,
                AEItemKey.of(result), definition.substitutes());
        return finish(level, encoded, recipe);
    }

    private Built stonecutting(Level level, PatternDefinition definition, List<AEKey> keys) {
        AEItemKey input = (AEItemKey) keys.get(0);
        SimpleContainer container = new SimpleContainer(input.toStack());
        // The recipe chooses the output, so it must be exactly the requested one: no fallback to another match.
        StonecutterRecipe recipe = byId(level, definition.recipeId(), StonecutterRecipe.class)
                .filter(candidate -> candidate.matches(container, level))
                .orElse(null);
        ItemStack result = recipe == null ? ItemStack.EMPTY : recipe.getResultItem(level.registryAccess());
        if (result.isEmpty()) {
            return noRecipe("This stonecutter recipe does not take this input");
        }
        ItemStack encoded = PatternDetailsHelper.encodeStonecuttingPattern(recipe, input, AEItemKey.of(result),
                definition.substitutes());
        return finish(level, encoded, recipe);
    }

    private static <T> Optional<T> byId(Level level, String recipeId, Class<T> type) {
        ResourceLocation id = recipeId == null ? null : ResourceLocation.tryParse(recipeId);
        if (id == null) {
            return Optional.empty();
        }
        return level.getRecipeManager().byKey(id).filter(type::isInstance).map(type::cast);
    }

    private static Built noRecipe(String message) {
        return Built.invalid(List.of(new PatternIssue(PatternIssue.NO_MATCHING_RECIPE, "recipeId", message)));
    }

    /** Decodes the freshly encoded pattern, exactly as a provider will: what does not decode is not usable. */
    private Built finish(Level level, ItemStack encoded, Recipe<?> recipe) {
        IPatternDetails details = PatternDetailsHelper.decodePattern(encoded, level);
        if (details == null) {
            return Built.invalid(List.of(new PatternIssue(PatternIssue.NOT_ENCODABLE, null,
                    "The encoded pattern would not be usable")));
        }
        return new Built(List.of(), encoded, details, amounts(details.getOutputs()),
                recipe == null ? null : recipe.getId().toString());
    }

    /** The AE2 key for a resource ID, or {@code null} after recording why there is none. */
    private AEKey resolve(IGrid grid, PatternStack stack, String field, List<PatternIssue> issues) {
        if (stack == null) {
            return null;
        }
        ResourceId id = stack.resource();
        AEKey key = registryKey(id);
        if (key == null) {
            key = storage.knownKey(id);
        }
        if (key == null) {
            // Variants (NBT) and addon resource types cannot be built from an ID: take them from the network.
            key = networkKey(grid, id);
        }
        if (key == null) {
            boolean known = id.type().equals("item") || id.type().equals("fluid");
            issues.add(known
                    ? new PatternIssue(PatternIssue.UNKNOWN_RESOURCE, field, "No such " + id.type() + ": " + id)
                    : new PatternIssue(PatternIssue.UNSUPPORTED_RESOURCE_TYPE, field,
                            "Resources of this type can only be used while the network stores or can craft them"));
        }
        return key;
    }

    private static AEKey registryKey(ResourceId id) {
        if (id.variant() != null) {
            return null;
        }
        ResourceLocation location = ResourceLocation.tryBuild(id.namespace(), id.path());
        if (location == null) {
            return null;
        }
        if (id.type().equals("item") && ForgeRegistries.ITEMS.containsKey(location)) {
            Item item = ForgeRegistries.ITEMS.getValue(location);
            return item == null || item == Items.AIR ? null : AEItemKey.of(item);
        }
        if (id.type().equals("fluid") && ForgeRegistries.FLUIDS.containsKey(location)) {
            Fluid fluid = ForgeRegistries.FLUIDS.getValue(location);
            return fluid == null || fluid == Fluids.EMPTY ? null : AEFluidKey.of(fluid);
        }
        return null;
    }

    private static AEKey networkKey(IGrid grid, ResourceId id) {
        for (var entry : grid.getStorageService().getCachedInventory()) {
            if (matches(entry.getKey(), id)) {
                return entry.getKey();
            }
        }
        for (AEKey craftable : grid.getCraftingService().getCraftables(key -> matches(key, id))) {
            return craftable;
        }
        return null;
    }

    private static boolean matches(AEKey key, ResourceId id) {
        ResourceLocation location = key.getId();
        return location.getNamespace().equals(id.namespace()) && location.getPath().equals(id.path())
                && Ae2StoragePlatform.typeId(key.getType()).equals(id.type())
                && Objects.equals(Ae2StoragePlatform.variant(key), id.variant());
    }

    // --- encoding -----------------------------------------------------------------------------------

    @Override
    @ServerThreadOnly
    public EncodeOutcome encode(String gridKey, PatternDefinition definition, String providerId, PlayerProfile actor) {
        Ae2Support.requireServerThread(server, "PatternPlatform.encode()");
        IGrid grid = Ae2Support.grid(gridKey);
        Level level = level(grid);
        Built built = build(grid, level, definition);
        if (!built.issues().isEmpty()) {
            boolean unsupported = built.issues().stream()
                    .allMatch(issue -> issue.code().equals(PatternIssue.UNSUPPORTED_RESOURCE_TYPE));
            return unsupported
                    ? new EncodeOutcome("UNSUPPORTED_RESOURCE_TYPE", built.issues(), List.of(), null, null, null, Map.of())
                    : EncodeOutcome.invalid(built.issues(), built.outputs());
        }
        List<PatternAmount> outputs = built.outputs();

        // Check the destination before anything is taken out of storage.
        PatternContainer provider = null;
        int slot = -1;
        if (providerId != null) {
            var found = findProvider(grid, providerId).orElse(null);
            if (found == null) {
                return EncodeOutcome.failed("PROVIDER_NOT_FOUND", outputs, Map.of());
            }
            if (!found.getValue()) {
                return EncodeOutcome.failed("PROVIDER_OFFLINE", outputs, Map.of());
            }
            provider = found.getKey();
            InternalInventory inventory = provider.getTerminalPatternInventory();
            boolean anyEmpty = false;
            for (int i = 0; i < inventory.size() && slot < 0; i++) {
                if (inventory.getStackInSlot(i).isEmpty()) {
                    anyEmpty = true;
                    if (inventory.insertItem(i, built.encoded().copy(), true).isEmpty()) {
                        slot = i;
                    }
                }
            }
            if (slot < 0) {
                return EncodeOutcome.failed(anyEmpty ? "DEPLOY_FAILED" : "PROVIDER_FULL", outputs, Map.of());
            }
        }

        IActionSource source = Ae2Support.actionSource(server, actor, level, grid);
        MEStorage inventory = grid.getStorageService().getInventory();
        IEnergyService energy = grid.getEnergyService();
        AEItemKey blank = blankPattern();
        AEItemKey encodedKey = AEItemKey.of(built.encoded());
        if (inventory.extract(blank, 1, Actionable.SIMULATE, source) < 1) {
            return EncodeOutcome.failed("NO_BLANK_PATTERN", outputs, Map.of());
        }
        if (StorageHelper.poweredExtraction(energy, inventory, blank, 1, source, Actionable.SIMULATE) < 1) {
            return EncodeOutcome.failed("NETWORK_NO_POWER", outputs, Map.of());
        }
        if (provider == null && inventory.insert(encodedKey, 1, Actionable.SIMULATE, source) < 1) {
            return EncodeOutcome.failed("ENCODE_FAILED", outputs, Map.of("reason", "STORAGE_FULL"));
        }

        if (StorageHelper.poweredExtraction(energy, inventory, blank, 1, source, Actionable.MODULATE) < 1) {
            return EncodeOutcome.failed("NO_BLANK_PATTERN", outputs, Map.of());
        }
        try {
            if (provider == null) {
                if (StorageHelper.poweredInsert(energy, inventory, encodedKey, 1, source, Actionable.MODULATE) < 1) {
                    return rollback("ENCODE_FAILED", outputs, energy, inventory, blank, source);
                }
                return EncodeOutcome.encoded(outputs, null, null, null);
            }

            InternalInventory patterns = provider.getTerminalPatternInventory();
            ItemStack remainder = patterns.insertItem(slot, built.encoded().copy(), false);
            if (!remainder.isEmpty()) {
                return rollback("DEPLOY_FAILED", outputs, energy, inventory, blank, source);
            }
            // Verify: the provider must now hold a pattern that decodes to exactly the pattern we built.
            ItemStack placed = patterns.getStackInSlot(slot);
            PatternContainers.Placement placement = PatternContainers.placement(provider);
            Level providerLevel = placement == null ? null : placement.level();
            IPatternDetails placedDetails = PatternDetailsHelper.decodePattern(placed, providerLevel != null ? providerLevel : level);
            if (!built.details().equals(placedDetails)) {
                if (ItemStack.isSameItemSameTags(placed, built.encoded())) {
                    patterns.extractItem(slot, 1, false);
                }
                return rollback("VERIFY_FAILED", outputs, energy, inventory, blank, source);
            }
            ResourceText name = describe(provider, true).name();
            return EncodeOutcome.encoded(outputs, providerId, name, slot);
        } catch (RuntimeException e) {
            LOGGER.warn("Encoding a pattern from the web UI failed; returning the Blank Pattern", e);
            return rollback("ENCODE_FAILED", outputs, energy, inventory, blank, source);
        }
    }

    /** Returns the Blank Pattern taken for a failed encode. */
    private static EncodeOutcome rollback(String errorCode, List<PatternAmount> outputs, IEnergyService energy,
                                          MEStorage inventory, AEItemKey blank, IActionSource source) {
        long returned = StorageHelper.poweredInsert(energy, inventory, blank, 1, source, Actionable.MODULATE);
        if (returned < 1) {
            // Energy ran out in the same tick: returning the pattern matters more than its power cost.
            returned = inventory.insert(blank, 1, Actionable.MODULATE, source);
        }
        Map<String, Object> details = new HashMap<>();
        details.put("blankReturned", returned >= 1);
        if (returned < 1) {
            LOGGER.error("ME Control Center could not return a Blank Pattern to ME storage after a failed encode ({}); "
                    + "the network's storage is full", errorCode);
        }
        return EncodeOutcome.failed(errorCode, outputs, details);
    }
}
