package io.github.codaaaaaa.mecc.forge.ae2;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CraftingJobStatus;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.KeyCounter;
import io.github.codaaaaaa.mecc.core.error.ErrorCode;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import io.github.codaaaaaa.mecc.core.resources.ResourceDescriptor;
import io.github.codaaaaaa.mecc.core.resources.ResourceDescriptor.ResourceUnit;
import io.github.codaaaaaa.mecc.core.resources.ResourceId;
import io.github.codaaaaaa.mecc.core.resources.ResourceIndex;
import io.github.codaaaaaa.mecc.core.resources.ResourceText;
import io.github.codaaaaaa.mecc.forge.ComponentText;
import io.github.codaaaaaa.mecc.platform.ServerThreadOnly;
import io.github.codaaaaaa.mecc.platform.StoragePlatform;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.tags.ITag;
import net.minecraftforge.registries.tags.ITagManager;

/**
 * Reads AE2 network storage (spec section 43). The server thread only copies AE2's already-maintained
 * cached inventory; identifiers, names, and units are derived afterwards on an ME Control Center worker thread from
 * immutable resource keys and frozen registries.
 */
public final class Ae2StoragePlatform implements StoragePlatform {
    private static final AtomicInteger TAGS_VERSION = new AtomicInteger();
    private static final int MAX_DESCRIPTOR_CACHE = 200_000;

    private final MinecraftServer server;
    /** Conversion is pure and stable per key, so results are reused across snapshots. */
    private final Map<AEKey, ResourceDescriptor> descriptors = new ConcurrentHashMap<>();
    /**
     * Keys by resource id, for every key ever described. Variant ids (NBT hashes) cannot be turned back into keys,
     * so this is how a pattern can name e.g. a configured programmed circuit the network does not store.
     */
    private final Map<ResourceId, AEKey> keysById = new ConcurrentHashMap<>();

    public Ae2StoragePlatform(MinecraftServer server) {
        this.server = server;
    }

    /** Called when data packs (and therefore tags) are reloaded. */
    public static void tagsReloaded() {
        TAGS_VERSION.incrementAndGet();
    }

    @Override
    public int tagsVersion() {
        return TAGS_VERSION.get();
    }

    private static final class Capture implements StorageCapture {
        private final Instant capturedAt = Instant.now();
        private final AEKey[] keys;
        private final long[] amounts;
        private final boolean[] craftable;
        private final long[] crafting;

        private Capture(AEKey[] keys, long[] amounts, boolean[] craftable, long[] crafting) {
            this.keys = keys;
            this.amounts = amounts;
            this.craftable = craftable;
            this.crafting = crafting;
        }

        @Override
        public Instant capturedAt() {
            return capturedAt;
        }

        @Override
        public int size() {
            return keys.length;
        }
    }

    @Override
    @ServerThreadOnly
    public StorageCapture capture(String gridKey) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("StoragePlatform.capture() must run on the server thread");
        }
        IGrid grid = GridRegistry.find(gridKey).orElseThrow(() ->
                new MeccException(ErrorCode.NETWORK_OFFLINE, "The ME network is not loaded right now"));

        KeyCounter inventory = grid.getStorageService().getCachedInventory();
        ICraftingService crafting = grid.getCraftingService();
        Set<AEKey> craftables = new HashSet<>(crafting.getCraftables(key -> true));
        Map<AEKey, Long> beingCrafted = new HashMap<>();
        for (ICraftingCPU cpu : crafting.getCpus()) {
            CraftingJobStatus status = cpu.getJobStatus();
            if (status != null && status.crafting() != null) {
                beingCrafted.merge(status.crafting().what(), status.crafting().amount(), Long::sum);
            }
        }

        // Craftable resources that are not in storage still belong in the terminal, with amount 0.
        List<AEKey> keys = new ArrayList<>(inventory.size() + craftables.size());
        List<Long> amounts = new ArrayList<>(keys.size());
        Set<AEKey> seen = new HashSet<>(Math.max(16, inventory.size() * 2));
        for (var entry : inventory) {
            if (entry.getLongValue() > 0 && seen.add(entry.getKey())) {
                keys.add(entry.getKey());
                amounts.add(entry.getLongValue());
            }
        }
        for (AEKey craftableKey : craftables) {
            if (seen.add(craftableKey)) {
                keys.add(craftableKey);
                amounts.add(0L);
            }
        }

        int size = keys.size();
        AEKey[] keyArray = keys.toArray(new AEKey[0]);
        long[] amountArray = new long[size];
        boolean[] craftableArray = new boolean[size];
        long[] craftingArray = new long[size];
        for (int i = 0; i < size; i++) {
            amountArray[i] = amounts.get(i);
            craftableArray[i] = craftables.contains(keyArray[i]);
            craftingArray[i] = beingCrafted.getOrDefault(keyArray[i], ResourceIndex.NOT_CRAFTING);
        }
        return new Capture(keyArray, amountArray, craftableArray, craftingArray);
    }

    @Override
    public ResourceIndex describe(StorageCapture capture) {
        Capture source = (Capture) capture;
        int size = source.keys.length;
        ResourceDescriptor[] descriptorArray = new ResourceDescriptor[size];
        for (int i = 0; i < size; i++) {
            descriptorArray[i] = describe(source.keys[i]);
        }
        if (descriptors.size() > MAX_DESCRIPTOR_CACHE) {
            descriptors.clear();
            keysById.clear();
        }
        return new ResourceIndex(source.capturedAt, descriptorArray, source.amounts, source.craftable, source.crafting);
    }

    /** Describes one key, reusing earlier conversions. Safe on any thread. */
    ResourceDescriptor describe(AEKey key) {
        return descriptors.computeIfAbsent(key, candidate -> {
            ResourceDescriptor descriptor = convert(candidate);
            keysById.putIfAbsent(descriptor.id(), candidate);
            return descriptor;
        });
    }

    /** The key behind a resource id that was described before, if any. Safe on any thread. */
    AEKey knownKey(ResourceId id) {
        return keysById.get(id);
    }

    static ResourceDescriptor convert(AEKey key) {
        AEKeyType type = key.getType();
        String typeId = typeId(type);
        ResourceLocation id = key.getId();
        ResourceId resourceId = new ResourceId(typeId, id.getNamespace(), id.getPath(), variant(key));
        ResourceUnit unit = type.getAmountPerUnit() > 1 && type.getUnitSymbol() != null
                ? new ResourceUnit(type.getUnitSymbol(), type.getAmountPerUnit())
                : null;
        String iconKey = (typeId.equals("item") || typeId.equals("fluid") ? typeId : "other")
                + "/" + id.getNamespace() + "/" + id.getPath();

        // The whole name structure travels, not just a key: mods compose names from a shared key and the
        // material as an argument ("%s Dust"), and only the structure survives translation into the
        // browser's language. AE2 builds the same component the game shows in its own terminal.
        ResourceText name = ComponentText.of(key.getDisplayName());

        String descriptionKey = null;
        if (key instanceof AEItemKey itemKey) {
            descriptionKey = itemKey.getItem().getDescriptionId(itemKey.getReadOnlyStack());
        } else if (key instanceof AEFluidKey fluidKey) {
            descriptionKey = fluidKey.getFluid().getFluidType().getDescriptionId();
        } else if (key.getDisplayName().getContents() instanceof TranslatableContents translatable) {
            descriptionKey = translatable.getKey();
        }
        return new ResourceDescriptor(resourceId, descriptionKey, name, key.getModId(), unit, iconKey);
    }

    /** {@code ae2:i} and {@code ae2:f} become {@code item} and {@code fluid}; addon types keep their id. */
    static String typeId(AEKeyType type) {
        ResourceLocation id = type.getId();
        if (id.equals(AEKeyType.items().getId())) {
            return "item";
        }
        if (id.equals(AEKeyType.fluids().getId())) {
            return "fluid";
        }
        String combined = (id.getNamespace() + "_" + id.getPath()).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        return combined.length() > 64 ? combined.substring(0, 64) : combined;
    }

    /** Stable short hash of the NBT that distinguishes this key from others with the same registry id. */
    static String variant(AEKey key) {
        CompoundTag tag = key instanceof AEItemKey itemKey ? itemKey.getTag()
                : key instanceof AEFluidKey fluidKey ? fluidKey.getTag() : null;
        if (tag == null || tag.isEmpty()) {
            return null;
        }
        try {
            // getAsString() sorts keys, so equal NBT always yields the same hash.
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(tag.getAsString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Override
    @ServerThreadOnly
    public Map<String, List<String>> captureTags() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("StoragePlatform.captureTags() must run on the server thread");
        }
        Map<String, List<String>> tags = new HashMap<>();
        collectTags(ForgeRegistries.ITEMS, "item", tags);
        collectTags(ForgeRegistries.FLUIDS, "fluid", tags);
        tags.replaceAll((resource, list) -> List.copyOf(list));
        return tags;
    }

    private static <T> void collectTags(IForgeRegistry<T> registry, String type, Map<String, List<String>> tags) {
        ITagManager<T> manager = registry.tags();
        if (manager == null) {
            return;
        }
        for (ITag<T> tag : manager) {
            String name = tag.getKey().location().toString();
            for (T value : tag) {
                ResourceLocation id = registry.getKey(value);
                if (id != null) {
                    tags.computeIfAbsent(type + ":" + id, key -> new ArrayList<>()).add(name);
                }
            }
        }
    }
}
