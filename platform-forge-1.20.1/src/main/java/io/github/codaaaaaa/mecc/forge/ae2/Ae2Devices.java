package io.github.codaaaaaa.mecc.forge.ae2;

import appeng.api.implementations.blockentities.IChestOrDrive;
import appeng.api.implementations.blockentities.IWirelessAccessPoint;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.IStorageProvider;
import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.blockentity.networking.ControllerBlockEntity;
import appeng.helpers.InterfaceLogicHost;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.parts.AEBasePart;
import io.github.codaaaaaa.mecc.core.explorer.DeviceKind;
import io.github.codaaaaaa.mecc.core.networks.BlockLocation;
import io.github.codaaaaaa.mecc.core.resources.ResourceDescriptor;
import io.github.codaaaaaa.mecc.platform.NetworkPlatform.DeviceGroupState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Groups a grid's nodes into the devices the Network Explorer shows (spec section 26). Walks every node once, so
 * it runs on request only, never per tick.
 *
 * <p>ponytail: devices are grouped by the item their node represents, which is how AE2 itself labels a node. That
 * covers addon blocks (MEGA, ExtendedAE, ...) without knowing them. A kind that cannot be told from the owner's
 * type stays {@link DeviceKind#OTHER} instead of being guessed.
 */
final class Ae2Devices {
    private Ae2Devices() {
    }

    /** Mutable accumulator per {@code kind + item}. */
    private static final class Group {
        private final DeviceKind kind;
        private final ResourceDescriptor item;
        private final List<BlockLocation> locations = new ArrayList<>();
        private int count;
        private int offline;
        private int channels;
        private boolean channelsKnown;
        private double idlePower;
        private boolean truncated;

        private Group(DeviceKind kind, ResourceDescriptor item) {
            this.kind = kind;
            this.item = item;
        }

        private DeviceGroupState toState() {
            return new DeviceGroupState(kind, item, count, offline, channelsKnown ? channels : null, idlePower,
                    locations, truncated);
        }
    }

    /** Every node of the grid, grouped. Server thread only. */
    static List<DeviceGroupState> groups(IGrid grid) {
        Map<String, Group> groups = new LinkedHashMap<>();
        for (IGridNode node : grid.getNodes()) {
            Object owner = node.getOwner();
            DeviceKind kind = kindOf(owner);
            ResourceDescriptor item = item(node);
            String key = kind.name() + "/" + (item == null ? "?" : item.id());
            Group group = groups.computeIfAbsent(key, ignored -> new Group(kind, item));
            group.count++;
            if (!node.isActive()) {
                group.offline++;
            }
            try {
                group.channels += node.getUsedChannels();
                group.channelsKnown = true;
            } catch (RuntimeException e) {
                // A node that cannot report its channels leaves the group's channel count unknown.
            }
            group.idlePower += node.getIdlePowerUsage();
            BlockLocation location = location(owner);
            if (location != null) {
                if (group.locations.size() < DeviceGroupState.MAX_LOCATIONS) {
                    group.locations.add(location);
                } else {
                    group.truncated = true;
                }
            }
        }
        List<DeviceGroupState> states = new ArrayList<>(groups.size());
        groups.values().forEach(group -> states.add(group.toState()));
        return states;
    }

    private static DeviceKind kindOf(Object owner) {
        if (owner instanceof IWirelessAccessPoint) {
            return DeviceKind.ACCESS_POINT;
        }
        if (owner instanceof ControllerBlockEntity) {
            return DeviceKind.CONTROLLER;
        }
        if (owner instanceof CraftingBlockEntity) {
            return DeviceKind.CRAFTING;
        }
        if (owner instanceof InterfaceLogicHost) {
            return DeviceKind.INTERFACE;
        }
        if (owner instanceof PatternContainer) {
            return DeviceKind.PATTERN_PROVIDER;
        }
        if (owner instanceof IChestOrDrive || owner instanceof IStorageProvider) {
            return DeviceKind.STORAGE;
        }
        return DeviceKind.OTHER;
    }

    /** The node's own item, as AE2 shows it in game. */
    private static ResourceDescriptor item(IGridNode node) {
        try {
            AEItemKey key = node.getVisualRepresentation();
            return key == null ? null : Ae2StoragePlatform.convert(key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static BlockLocation location(Object owner) {
        BlockEntity blockEntity = owner instanceof AEBasePart part ? part.getBlockEntity()
                : owner instanceof BlockEntity entity ? entity : null;
        Level level = blockEntity == null ? null : blockEntity.getLevel();
        if (level == null) {
            return null;
        }
        return new BlockLocation(level.dimension().location().toString(), blockEntity.getBlockPos().getX(),
                blockEntity.getBlockPos().getY(), blockEntity.getBlockPos().getZ());
    }
}
