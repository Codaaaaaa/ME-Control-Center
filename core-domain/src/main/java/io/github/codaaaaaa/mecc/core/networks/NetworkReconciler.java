package io.github.codaaaaaa.mecc.core.networks;

import io.github.codaaaaaa.mecc.core.networks.DiscoverySnapshot.AnchorProbe;
import io.github.codaaaaaa.mecc.core.networks.DiscoverySnapshot.DiscoveredGrid;
import io.github.codaaaaaa.mecc.core.networks.DiscoverySnapshot.ObservedAnchor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Maps persistent ME Control Center network records onto the grids found by a discovery pass (spec section 29).
 *
 * <p>Rules, deliberately conservative:
 * <ul>
 *   <li>An anchor matches an observed anchor only at the same location <em>and</em> with the same node
 *       owner, so a foreign anchor placed where an old one stood never inherits its network.</li>
 *   <li>A record resolves to grid G only if all of its matched anchors are in G and G holds no other
 *       record's anchors. Otherwise it is {@link NetworkRecordStatus#CONFLICT} and resolves to nothing:
 *       records are never merged and live data is never shared across records implicitly.</li>
 *   <li>A record with no matched anchor is {@link NetworkRecordStatus#OFFLINE}.</li>
 *   <li>Anchors are pruned only when removal is certain: the chunk is loaded and the block is gone, or
 *       a different owner's anchor now stands there. Anchors in unloaded chunks are kept.</li>
 *   <li>New anchors of an unambiguously resolved grid are attached to its record so identity survives
 *       the original anchor being replaced, but only when placed by an owner of an already matched
 *       anchor of that record.</li>
 * </ul>
 *
 * Pure function; safe to call from any thread.
 */
public final class NetworkReconciler {
    private NetworkReconciler() {
    }

    public enum ConflictReason {
        /** The record's anchors are in more than one grid. */
        SPLIT,
        /** The record's grid also contains another record's anchors. */
        MERGED
    }

    /**
     * @param status resolution status
     * @param reason conflict reason, only for {@link NetworkRecordStatus#CONFLICT}
     * @param grid   the resolved grid, only for {@link NetworkRecordStatus#ONLINE}
     */
    public record Resolution(NetworkRecordStatus status, ConflictReason reason, DiscoveredGrid grid) {
    }

    public record AnchorAddition(UUID networkId, ObservedAnchor anchor) {
    }

    public record AnchorRemoval(UUID networkId, BlockLocation location) {
    }

    /**
     * @param resolutions     one entry per record, in input order
     * @param anchorsToRemove anchors proven stale
     * @param anchorsToAdd    new anchors of resolved grids
     * @param unenrolled      loaded grids that belong to no record (enrollment candidates)
     */
    public record Result(
            Map<UUID, Resolution> resolutions,
            List<AnchorRemoval> anchorsToRemove,
            List<AnchorAddition> anchorsToAdd,
            List<DiscoveredGrid> unenrolled) {
    }

    public static Result reconcile(Collection<WebNetwork> records, DiscoverySnapshot snapshot) {
        Map<BlockLocation, ObservedAnchor> observed = new HashMap<>();
        Map<BlockLocation, DiscoveredGrid> gridByLocation = new HashMap<>();
        for (DiscoveredGrid grid : snapshot.grids()) {
            for (ObservedAnchor anchor : grid.anchors()) {
                observed.put(anchor.location(), anchor);
                gridByLocation.put(anchor.location(), grid);
            }
        }

        List<AnchorRemoval> toRemove = new ArrayList<>();
        Map<UUID, Set<String>> gridsOfRecord = new LinkedHashMap<>();
        Map<String, Set<UUID>> recordsOfGrid = new HashMap<>();

        for (WebNetwork record : records) {
            Set<String> grids = new LinkedHashSet<>();
            for (NetworkAnchor anchor : record.anchors()) {
                ObservedAnchor seen = observed.get(anchor.location());
                if (seen != null) {
                    if (Objects.equals(seen.ownerUuid(), anchor.ownerUuid())) {
                        String key = gridByLocation.get(anchor.location()).runtimeKey();
                        grids.add(key);
                        recordsOfGrid.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(record.id());
                    } else {
                        toRemove.add(new AnchorRemoval(record.id(), anchor.location()));
                    }
                } else if (snapshot.probes().get(anchor.location()) == AnchorProbe.ABSENT) {
                    toRemove.add(new AnchorRemoval(record.id(), anchor.location()));
                }
            }
            gridsOfRecord.put(record.id(), grids);
        }

        Map<String, DiscoveredGrid> gridsByKey = new HashMap<>();
        snapshot.grids().forEach(grid -> gridsByKey.put(grid.runtimeKey(), grid));

        Map<UUID, Resolution> resolutions = new LinkedHashMap<>();
        List<AnchorAddition> toAdd = new ArrayList<>();
        for (WebNetwork record : records) {
            Set<String> grids = gridsOfRecord.get(record.id());
            if (grids.isEmpty()) {
                resolutions.put(record.id(), new Resolution(NetworkRecordStatus.OFFLINE, null, null));
                continue;
            }
            boolean merged = grids.stream().anyMatch(key -> recordsOfGrid.get(key).size() > 1);
            if (merged || grids.size() > 1) {
                ConflictReason reason = merged ? ConflictReason.MERGED : ConflictReason.SPLIT;
                resolutions.put(record.id(), new Resolution(NetworkRecordStatus.CONFLICT, reason, null));
                continue;
            }
            DiscoveredGrid grid = gridsByKey.get(grids.iterator().next());
            resolutions.put(record.id(), new Resolution(NetworkRecordStatus.ONLINE, null, grid));
            Set<BlockLocation> known = new HashSet<>();
            Set<UUID> trustedOwners = new HashSet<>();
            for (NetworkAnchor anchor : record.anchors()) {
                known.add(anchor.location());
                ObservedAnchor seen = observed.get(anchor.location());
                if (seen != null && Objects.equals(seen.ownerUuid(), anchor.ownerUuid()) && anchor.ownerUuid() != null) {
                    trustedOwners.add(anchor.ownerUuid());
                }
            }
            for (ObservedAnchor anchor : grid.anchors()) {
                // Only adopt anchors of owners already vouching for this record, so a foreign anchor that
                // is connected briefly cannot later tie the record to another grid.
                if (!known.contains(anchor.location()) && anchor.ownerUuid() != null
                        && trustedOwners.contains(anchor.ownerUuid())) {
                    toAdd.add(new AnchorAddition(record.id(), anchor));
                }
            }
        }

        List<DiscoveredGrid> unenrolled = snapshot.grids().stream()
                .filter(grid -> !recordsOfGrid.containsKey(grid.runtimeKey()))
                .toList();
        return new Result(resolutions, List.copyOf(toRemove), List.copyOf(toAdd), unenrolled);
    }
}
