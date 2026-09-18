package io.github.codaaaaaa.mecc.forge.ae2;

import appeng.api.networking.GridServices;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Grids that currently contain at least one anchor. Mutated and read on the server thread only, so it
 * needs no synchronization.
 */
public final class GridRegistry {
    private static final Set<MeccGridTrackerService> TRACKED = new LinkedHashSet<>();

    private GridRegistry() {
    }

    /** Registers ME Control Center's grid service with AE2. Must run before any grid is created (mod construction). */
    public static void registerGridService() {
        GridServices.register(MeccGridTracker.class, MeccGridTrackerService.class);
    }

    static void track(MeccGridTrackerService tracker) {
        TRACKED.add(tracker);
    }

    static void untrack(MeccGridTrackerService tracker) {
        TRACKED.remove(tracker);
    }

    static List<MeccGridTrackerService> tracked() {
        return new ArrayList<>(TRACKED);
    }

    /** The grid behind a runtime key from a discovery snapshot, if it still exists. Server thread only. */
    static Optional<appeng.api.networking.IGrid> find(String runtimeKey) {
        for (MeccGridTrackerService tracker : TRACKED) {
            if (tracker.runtimeKey().equals(runtimeKey)) {
                return Optional.of(tracker.grid());
            }
        }
        return Optional.empty();
    }

    /** Forgets all grids, e.g. when an integrated server stops and another world may be opened. */
    public static void clear() {
        TRACKED.clear();
    }
}
