package io.github.codaaaaaa.mecc.core.networks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.codaaaaaa.mecc.core.networks.DiscoverySnapshot.AnchorProbe;
import io.github.codaaaaaa.mecc.core.networks.DiscoverySnapshot.DiscoveredGrid;
import io.github.codaaaaaa.mecc.core.networks.DiscoverySnapshot.ObservedAnchor;
import io.github.codaaaaaa.mecc.core.networks.NetworkReconciler.AnchorRemoval;
import io.github.codaaaaaa.mecc.core.networks.NetworkReconciler.ConflictReason;
import io.github.codaaaaaa.mecc.core.networks.NetworkReconciler.Result;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NetworkReconcilerTest {
    private static final Instant NOW = Instant.parse("2026-09-17T12:00:00Z");
    private static final UUID STEVE = UUID.randomUUID();
    private static final UUID ALEX = UUID.randomUUID();
    private static final GridStatus STATUS = new GridStatus(true, false, "CONTROLLER_ONLINE", null,
            1000.0, 2000.0, 5.0, 10.0, 12, 40, 100, 2, 1, null);

    private static BlockLocation at(int x) {
        return new BlockLocation("minecraft:overworld", x, 64, 0);
    }

    private static WebNetwork record(UUID owner, BlockLocation... anchors) {
        return new WebNetwork(UUID.randomUUID(), "net", owner, NOW, null, NetworkRecordStatus.OFFLINE,
                Arrays.stream(anchors).map(location -> new NetworkAnchor(location, owner, NOW)).toList());
    }

    private static DiscoveredGrid grid(String key, UUID owner, BlockLocation... anchors) {
        return new DiscoveredGrid(key,
                Arrays.stream(anchors).map(location -> new ObservedAnchor(location, owner, true)).toList(), STATUS);
    }

    private static DiscoverySnapshot snapshot(DiscoveredGrid... grids) {
        return new DiscoverySnapshot(NOW, List.of(grids), Map.of());
    }

    @Test
    void resolvesRecordToItsGridAndListsOthersAsUnenrolled() {
        WebNetwork steves = record(STEVE, at(1));
        DiscoveredGrid steveGrid = grid("g1", STEVE, at(1));
        DiscoveredGrid alexGrid = grid("g2", ALEX, at(9));

        Result result = NetworkReconciler.reconcile(List.of(steves), snapshot(steveGrid, alexGrid));

        assertEquals(NetworkRecordStatus.ONLINE, result.resolutions().get(steves.id()).status());
        assertEquals(steveGrid, result.resolutions().get(steves.id()).grid());
        assertEquals(List.of(alexGrid), result.unenrolled());
    }

    @Test
    void unloadedRecordIsOfflineAndKeepsItsAnchors() {
        WebNetwork steves = record(STEVE, at(1));
        DiscoverySnapshot snapshot = new DiscoverySnapshot(NOW, List.of(), Map.of(at(1), AnchorProbe.UNLOADED));

        Result result = NetworkReconciler.reconcile(List.of(steves), snapshot);

        assertEquals(NetworkRecordStatus.OFFLINE, result.resolutions().get(steves.id()).status());
        assertTrue(result.anchorsToRemove().isEmpty());
    }

    @Test
    void mergedGridsNeverMergeRecords() {
        WebNetwork steves = record(STEVE, at(1));
        WebNetwork alexs = record(ALEX, at(2));
        DiscoveredGrid joined = new DiscoveredGrid("g1", List.of(
                new ObservedAnchor(at(1), STEVE, true), new ObservedAnchor(at(2), ALEX, true)), STATUS);

        Result result = NetworkReconciler.reconcile(List.of(steves, alexs), snapshot(joined));

        for (WebNetwork network : List.of(steves, alexs)) {
            assertEquals(NetworkRecordStatus.CONFLICT, result.resolutions().get(network.id()).status());
            assertEquals(ConflictReason.MERGED, result.resolutions().get(network.id()).reason());
            assertNull(result.resolutions().get(network.id()).grid(), "no live access while ambiguous");
        }
        assertTrue(result.unenrolled().isEmpty());
    }

    @Test
    void splitRecordIsConflict() {
        WebNetwork steves = record(STEVE, at(1), at(2));
        Result result = NetworkReconciler.reconcile(List.of(steves),
                snapshot(grid("g1", STEVE, at(1)), grid("g2", STEVE, at(2))));

        assertEquals(NetworkRecordStatus.CONFLICT, result.resolutions().get(steves.id()).status());
        assertEquals(ConflictReason.SPLIT, result.resolutions().get(steves.id()).reason());
    }

    @Test
    void foreignAnchorAtAnOldLocationDoesNotInheritTheNetwork() {
        WebNetwork steves = record(STEVE, at(1));
        DiscoveredGrid alexGrid = grid("g1", ALEX, at(1));

        Result result = NetworkReconciler.reconcile(List.of(steves), snapshot(alexGrid));

        assertEquals(NetworkRecordStatus.OFFLINE, result.resolutions().get(steves.id()).status());
        assertEquals(List.of(new AnchorRemoval(steves.id(), at(1))), result.anchorsToRemove());
        assertEquals(List.of(alexGrid), result.unenrolled());
    }

    @Test
    void removedAnchorInLoadedChunkIsPruned() {
        WebNetwork steves = record(STEVE, at(1), at(2));
        DiscoverySnapshot snapshot = new DiscoverySnapshot(NOW, List.of(grid("g1", STEVE, at(1))),
                Map.of(at(2), AnchorProbe.ABSENT));

        Result result = NetworkReconciler.reconcile(List.of(steves), snapshot);

        assertEquals(NetworkRecordStatus.ONLINE, result.resolutions().get(steves.id()).status());
        assertEquals(List.of(new AnchorRemoval(steves.id(), at(2))), result.anchorsToRemove());
    }

    @Test
    void attachesNewAnchorsOnlyFromTrustedOwners() {
        WebNetwork steves = record(STEVE, at(1));
        DiscoveredGrid grid = new DiscoveredGrid("g1", List.of(
                new ObservedAnchor(at(1), STEVE, true),
                new ObservedAnchor(at(2), STEVE, true),
                new ObservedAnchor(at(3), ALEX, true)), STATUS);

        Result result = NetworkReconciler.reconcile(List.of(steves), snapshot(grid));

        assertEquals(1, result.anchorsToAdd().size());
        assertEquals(at(2), result.anchorsToAdd().get(0).anchor().location());
    }

    @Test
    void parsesLocationKeys() {
        BlockLocation location = new BlockLocation("mod_id:space/moon", -12, -60, 300);
        assertEquals(location, BlockLocation.parseKey(location.key()).orElseThrow());
        assertTrue(BlockLocation.parseKey("overworld@1,2,3").isEmpty());
        assertTrue(BlockLocation.parseKey("minecraft:overworld@1,2").isEmpty());
    }
}
