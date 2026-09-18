package io.github.codaaaaaa.mecc.forge.ae2;

import appeng.api.implementations.blockentities.IWirelessAccessPoint;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridServiceProvider;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.nbt.CompoundTag;

/**
 * Tracks anchor nodes of one grid. Instantiated by AE2 through {@link appeng.api.networking.GridServices}
 * (hence public with a single public constructor). AE2 calls node callbacks on the server thread only.
 */
public final class MeccGridTrackerService implements MeccGridTracker, IGridServiceProvider {
    private static final AtomicLong SERIALS = new AtomicLong();

    private final IGrid grid;
    private final long serial = SERIALS.incrementAndGet();
    private final Set<IGridNode> anchors = new LinkedHashSet<>();

    public MeccGridTrackerService(IGrid grid) {
        this.grid = grid;
    }

    IGrid grid() {
        return grid;
    }

    /** Identifies this grid object for the lifetime of the process; never persisted. */
    String runtimeKey() {
        return "grid-" + serial;
    }

    @Override
    public Collection<IGridNode> anchorNodes() {
        return Collections.unmodifiableSet(anchors);
    }

    @Override
    public void addNode(IGridNode node, CompoundTag savedData) {
        if (node.getOwner() instanceof IWirelessAccessPoint && anchors.add(node) && anchors.size() == 1) {
            GridRegistry.track(this);
        }
    }

    @Override
    public void removeNode(IGridNode node) {
        if (anchors.remove(node) && anchors.isEmpty()) {
            GridRegistry.untrack(this);
        }
    }
}
