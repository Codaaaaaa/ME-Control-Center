package io.github.codaaaaaa.mecc.core.networks;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Result of one discovery pass, captured on the server thread.
 *
 * @param capturedAt when the pass ran
 * @param grids      every loaded grid that has at least one anchor
 * @param probes     state of each previously known anchor location that is not part of {@code grids}
 */
public record DiscoverySnapshot(Instant capturedAt, List<DiscoveredGrid> grids, Map<BlockLocation, AnchorProbe> probes) {

    public DiscoverySnapshot {
        Objects.requireNonNull(capturedAt, "capturedAt");
        grids = List.copyOf(grids);
        probes = Map.copyOf(probes);
    }

    public static DiscoverySnapshot empty(Instant capturedAt) {
        return new DiscoverySnapshot(capturedAt, List.of(), Map.of());
    }

    /**
     * A loaded grid that could be (or is) an ME Control Center network.
     *
     * @param runtimeKey identifier valid only for the lifetime of this grid object; never persisted or exposed
     * @param anchors    anchor blocks connected to this grid
     * @param status     live status
     */
    public record DiscoveredGrid(String runtimeKey, List<ObservedAnchor> anchors, GridStatus status) {
        public DiscoveredGrid {
            Objects.requireNonNull(runtimeKey, "runtimeKey");
            Objects.requireNonNull(status, "status");
            anchors = anchors.stream().sorted((a, b) -> a.location().compareTo(b.location())).toList();
        }
    }

    /**
     * @param ownerUuid owning player of the anchor node, or {@code null}
     * @param active    whether the anchor itself is powered and has a channel
     */
    public record ObservedAnchor(BlockLocation location, UUID ownerUuid, boolean active) {
        public ObservedAnchor {
            Objects.requireNonNull(location, "location");
        }
    }

    /** What the platform found at a known anchor location that did not appear in any grid. */
    public enum AnchorProbe {
        /** The chunk is not loaded; nothing can be concluded. */
        UNLOADED,
        /** An anchor block exists but is not (yet) part of a grid, e.g. its node has not initialized. */
        PRESENT_UNCONNECTED,
        /** The chunk is loaded and there is no anchor block: the anchor was removed. */
        ABSENT
    }
}
