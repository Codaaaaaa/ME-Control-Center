package io.github.codaaaaaa.mecc.forge.ae2;

import appeng.api.implementations.blockentities.IWirelessAccessPoint;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.pathing.IPathingService;
import appeng.api.util.DimensionalBlockPos;
import io.github.codaaaaaa.mecc.core.networks.BlockLocation;
import io.github.codaaaaaa.mecc.core.networks.DiscoverySnapshot;
import io.github.codaaaaaa.mecc.core.networks.DiscoverySnapshot.AnchorProbe;
import io.github.codaaaaaa.mecc.core.networks.DiscoverySnapshot.DiscoveredGrid;
import io.github.codaaaaaa.mecc.core.networks.DiscoverySnapshot.ObservedAnchor;
import io.github.codaaaaaa.mecc.core.networks.GridStatus;
import io.github.codaaaaaa.mecc.platform.NetworkPlatform;
import io.github.codaaaaaa.mecc.platform.NetworkPlatform.DeviceCapture;
import io.github.codaaaaaa.mecc.platform.NetworkPlatform.DeviceGroupState;
import io.github.codaaaaaa.mecc.platform.NetworkPlatform.RequesterCapture;
import io.github.codaaaaaa.mecc.platform.ServerThreadOnly;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AE2 15 network discovery. Wireless Access Points are the network anchors (spec section 29).
 * Cost is proportional to tracked anchors and known anchor locations, never to world or grid size.
 */
public final class Ae2NetworkPlatform implements NetworkPlatform {
    private static final Logger LOGGER = LoggerFactory.getLogger(Ae2NetworkPlatform.class);

    private final MinecraftServer server;

    public Ae2NetworkPlatform(MinecraftServer server) {
        this.server = server;
    }

    @Override
    @ServerThreadOnly
    public DiscoverySnapshot discover(Collection<BlockLocation> knownAnchors) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("NetworkPlatform.discover() must run on the server thread");
        }
        List<DiscoveredGrid> grids = new ArrayList<>();
        Set<BlockLocation> seen = new HashSet<>();
        for (MeccGridTrackerService tracker : GridRegistry.tracked()) {
            List<ObservedAnchor> anchors = new ArrayList<>();
            for (IGridNode node : tracker.anchorNodes()) {
                if (node.getOwner() instanceof IWirelessAccessPoint accessPoint) {
                    DimensionalBlockPos position = accessPoint.getLocation();
                    Level level = position.getLevel();
                    if (level == null) {
                        continue;
                    }
                    BlockPos pos = position.getPos();
                    BlockLocation location = new BlockLocation(level.dimension().location().toString(),
                            pos.getX(), pos.getY(), pos.getZ());
                    if (seen.add(location)) {
                        anchors.add(new ObservedAnchor(location, node.getOwningPlayerProfileId(), accessPoint.isActive()));
                    }
                }
            }
            if (!anchors.isEmpty()) {
                grids.add(new DiscoveredGrid(tracker.runtimeKey(), anchors, status(tracker.grid())));
            }
        }

        Map<BlockLocation, AnchorProbe> probes = new HashMap<>();
        for (BlockLocation location : knownAnchors) {
            if (!seen.contains(location)) {
                probes.put(location, probe(location));
            }
        }
        return new DiscoverySnapshot(Instant.now(), grids, probes);
    }

    @Override
    @ServerThreadOnly
    public RequesterCapture captureRequesters(String gridKey) {
        Ae2Support.requireServerThread(server, "NetworkPlatform.captureRequesters()");
        return new RequesterCapture(MeRequesters.present(), MeRequesters.capture(Ae2Support.grid(gridKey)));
    }

    @Override
    @ServerThreadOnly
    public boolean clearRequest(String gridKey, String requesterId, int slot) {
        Ae2Support.requireServerThread(server, "NetworkPlatform.clearRequest()");
        return MeRequesters.clear(Ae2Support.grid(gridKey), requesterId, slot);
    }

    @Override
    @ServerThreadOnly
    public DeviceCapture describeDevices(String gridKey) {
        Ae2Support.requireServerThread(server, "NetworkPlatform.describeDevices()");
        IGrid grid = Ae2Support.grid(gridKey);
        List<DeviceGroupState> groups = Ae2Devices.groups(grid);
        int nodes = 0;
        int offline = 0;
        for (DeviceGroupState group : groups) {
            nodes += group.count();
            offline += group.offline();
        }
        return new DeviceCapture(Instant.now(), status(grid), groups, nodes, offline);
    }

    private AnchorProbe probe(BlockLocation location) {
        ResourceLocation dimension = ResourceLocation.tryParse(location.dimension());
        ServerLevel level = dimension == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
        BlockPos pos = new BlockPos(location.x(), location.y(), location.z());
        if (level == null || !level.isLoaded(pos)) {
            return AnchorProbe.UNLOADED;
        }
        return level.getBlockEntity(pos) instanceof IWirelessAccessPoint ? AnchorProbe.PRESENT_UNCONNECTED : AnchorProbe.ABSENT;
    }

    private static GridStatus status(IGrid grid) {
        IEnergyService energy = grid.getEnergyService();
        IPathingService pathing = grid.getPathingService();
        ICraftingService crafting = grid.getCraftingService();
        Collection<ICraftingCPU> cpus = optional(crafting::getCpus);
        return new GridStatus(
                energy.isNetworkPowered(),
                pathing.isNetworkBooting(),
                optional(() -> pathing.getControllerState().name()),
                optional(() -> pathing.getChannelMode().name()),
                optional(energy::getStoredPower),
                optional(energy::getMaxStoredPower),
                optional(energy::getAvgPowerUsage),
                optional(energy::getAvgPowerInjection),
                optional(pathing::getUsedChannels),
                grid.size(),
                optional(() -> grid.getStorageService().getCachedInventory().size()),
                cpus == null ? null : cpus.size(),
                cpus == null ? null : (int) cpus.stream().filter(ICraftingCPU::isBusy).count(),
                optional(() -> Ae2PatternPlatform.countOnlineProviders(grid)));
    }

    /** Reads an optional capability; a failure means "not reliably available", never zero. */
    private static <T> T optional(Supplier<T> reader) {
        try {
            return reader.get();
        } catch (RuntimeException e) {
            LOGGER.debug("AE2 grid value unavailable", e);
            return null;
        }
    }
}
