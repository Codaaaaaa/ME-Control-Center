package io.github.codaaaaaa.mecc.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.codaaaaaa.mecc.core.crafting.OrderTarget;
import io.github.codaaaaaa.mecc.core.insights.SampleRepository.SeriesPoint;
import io.github.codaaaaaa.mecc.core.insights.SampleResolution;
import io.github.codaaaaaa.mecc.core.insights.WatchEntry;
import io.github.codaaaaaa.mecc.core.networks.NetworkRecordStatus;
import io.github.codaaaaaa.mecc.core.networks.WebNetwork;
import io.github.codaaaaaa.mecc.core.persistence.DuplicateKeyException;
import io.github.codaaaaaa.mecc.core.resources.ResourceId;
import io.github.codaaaaaa.mecc.core.users.PlayerProfile;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteInsightsTest {
    /** An hour boundary, so bucket arithmetic is easy to follow. */
    private static final Instant T0 = Instant.parse("2026-09-18T10:00:00Z");
    private static final ResourceId IRON = ResourceId.of("item", "minecraft", "iron_ingot");
    private static final ResourceId GOLD = ResourceId.of("item", "minecraft", "gold_ingot");

    @TempDir
    Path dir;

    private SqliteDataStore store;
    private final PlayerProfile steve = new PlayerProfile(UUID.randomUUID(), "Steve");
    private final PlayerProfile alex = new PlayerProfile(UUID.randomUUID(), "Alex");
    private final UUID network = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        store = SqliteDataStore.open(dir.resolve("mecc.db"));
        store.write(repos -> {
            repos.users().upsert(steve, T0);
            repos.users().upsert(alex, T0);
            repos.networks().insert(new WebNetwork(network, "Base", steve.uuid(), T0, T0, NetworkRecordStatus.ONLINE,
                    List.of()));
            return null;
        }).get();
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private static OrderTarget target(ResourceId id) {
        return new OrderTarget(id, Map.of("en_us", "Iron Ingot"), "minecraft", "item/minecraft/iron_ingot", null);
    }

    @Test
    void watchlistEntriesAreUniquePerPlayerAndSharedSeriesAreDeduplicated() throws Exception {
        WatchEntry steveIron = new WatchEntry(UUID.randomUUID(), steve.uuid(), network, target(IRON), T0);
        store.write(repos -> {
            repos.watchlist().insert(steveIron);
            repos.watchlist().insert(new WatchEntry(UUID.randomUUID(), alex.uuid(), network, target(IRON), T0));
            repos.watchlist().insert(new WatchEntry(UUID.randomUUID(), alex.uuid(), network, target(GOLD), T0.plusSeconds(1)));
            return null;
        }).get();

        assertEquals(Map.of(network, Set.of(IRON, GOLD)), store.read(repos -> repos.watchlist().watchedSeries()).get());
        assertEquals(List.of(IRON, GOLD), store.read(repos -> repos.watchlist().list(alex.uuid(), network)).get().stream()
                .map(entry -> entry.resource().resourceId()).toList());
        assertEquals(steveIron, store.read(repos -> repos.watchlist().find(steve.uuid(), network, IRON)).get().orElseThrow());

        ExecutionException duplicate = assertThrows(ExecutionException.class, () -> store.write(repos -> {
            repos.watchlist().insert(new WatchEntry(UUID.randomUUID(), steve.uuid(), network, target(IRON), T0));
            return null;
        }).get());
        assertInstanceOf(DuplicateKeyException.class, duplicate.getCause());

        // Deleting the network deletes its watchlist entries.
        store.write(repos -> repos.networks().delete(network)).get();
        assertEquals(0, store.read(repos -> repos.watchlist().countByPlayer(alex.uuid())).get());
    }

    @Test
    void aggregatesKeepFirstLastMinMaxAndAverageAndIgnoreRepeatedSamples() throws Exception {
        long[] amounts = {100, 40, 70, 90};
        store.write(repos -> {
            for (int i = 0; i < amounts.length; i++) {
                repos.samples().record(network, T0.plusSeconds(15L * i), Map.of(IRON, amounts[i]));
            }
            // The same snapshot recorded twice must not count twice.
            repos.samples().record(network, T0.plusSeconds(45), Map.of(IRON, 90L));
            return null;
        }).get();

        List<SeriesPoint> minute = store.read(repos -> repos.samples().series(network, IRON, SampleResolution.ONE_MINUTE,
                T0, T0.plusSeconds(3600), 60_000)).get();
        assertEquals(List.of(new SeriesPoint(T0, 75, 40, 100)), minute);

        List<SeriesPoint> raw = store.read(repos -> repos.samples().series(network, IRON, SampleResolution.RAW,
                T0, T0.plusSeconds(3600), 30_000)).get();
        assertEquals(List.of(new SeriesPoint(T0, 70, 40, 100), new SeriesPoint(T0.plusSeconds(30), 80, 70, 90)), raw,
                "raw samples grouped into 30 s steps");

        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("mecc.db"));
             var rows = connection.createStatement().executeQuery(
                     "SELECT first_amount, last_amount, sample_count FROM resource_samples_1h")) {
            assertTrue(rows.next());
            assertEquals(100, rows.getLong(1));
            assertEquals(90, rows.getLong(2));
            assertEquals(4, rows.getInt(3));
        }
    }

    @Test
    void purgesByRetentionAndReportsTheOldestSample() throws Exception {
        store.write(repos -> {
            repos.samples().record(network, T0, Map.of(IRON, 1L));
            repos.samples().record(network, T0.plusSeconds(7200), Map.of(IRON, 2L));
            return null;
        }).get();
        assertEquals(T0, store.read(repos -> repos.samples().oldest(network, IRON)).get().orElseThrow());

        assertEquals(1, store.write(repos -> repos.samples().purge(SampleResolution.RAW, T0.plusSeconds(1))).get());
        assertEquals(1, store.write(repos -> repos.samples().purge(SampleResolution.ONE_MINUTE, T0.plusSeconds(1))).get());
        assertEquals(1, store.write(repos -> repos.samples().purge(SampleResolution.FIVE_MINUTES, T0.plusSeconds(1))).get());
        // The hourly aggregate still holds the first sample.
        assertEquals(T0, store.read(repos -> repos.samples().oldest(network, IRON)).get().orElseThrow());
        assertEquals(1, store.write(repos -> repos.samples().purge(SampleResolution.ONE_HOUR, T0.plusSeconds(1))).get());
        assertEquals(T0.plusSeconds(7200), store.read(repos -> repos.samples().oldest(network, IRON)).get().orElseThrow());
    }
}
