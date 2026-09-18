package io.github.codaaaaaa.mecc.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.codaaaaaa.mecc.core.audit.AuditAction;
import io.github.codaaaaaa.mecc.core.audit.AuditEvent;
import io.github.codaaaaaa.mecc.core.audit.AuditEvent.AuditResult;
import io.github.codaaaaaa.mecc.core.auth.Device;
import io.github.codaaaaaa.mecc.core.crafting.CraftingOrder;
import io.github.codaaaaaa.mecc.core.crafting.CraftingOrderRepository;
import io.github.codaaaaaa.mecc.core.crafting.OrderEvent;
import io.github.codaaaaaa.mecc.core.crafting.OrderEventType;
import io.github.codaaaaaa.mecc.core.crafting.OrderSource;
import io.github.codaaaaaa.mecc.core.crafting.OrderState;
import io.github.codaaaaaa.mecc.core.crafting.OrderTarget;
import io.github.codaaaaaa.mecc.core.patterns.PatternDefinition;
import io.github.codaaaaaa.mecc.core.patterns.PatternDeployment;
import io.github.codaaaaaa.mecc.core.patterns.PatternDraft;
import io.github.codaaaaaa.mecc.core.patterns.PatternStack;
import io.github.codaaaaaa.mecc.core.patterns.PatternType;
import io.github.codaaaaaa.mecc.core.resources.ResourceDescriptor.ResourceUnit;
import io.github.codaaaaaa.mecc.core.resources.ResourceId;
import io.github.codaaaaaa.mecc.core.networks.BlockLocation;
import io.github.codaaaaaa.mecc.core.networks.NetworkAnchor;
import io.github.codaaaaaa.mecc.core.networks.NetworkMember;
import io.github.codaaaaaa.mecc.core.networks.NetworkRecordStatus;
import io.github.codaaaaaa.mecc.core.networks.WebNetwork;
import io.github.codaaaaaa.mecc.core.permissions.NetworkRole;
import io.github.codaaaaaa.mecc.core.persistence.DuplicateKeyException;
import io.github.codaaaaaa.mecc.core.users.PlayerProfile;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteDataStoreTest {
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @TempDir
    Path dir;

    private SqliteDataStore store;
    private final PlayerProfile steve = new PlayerProfile(UUID.randomUUID(), "Steve");

    @BeforeEach
    void setUp() throws Exception {
        store = SqliteDataStore.open(dir.resolve("mecc.db"));
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void migratesOnceAndUsesWal() throws Exception {
        store.close();
        store = SqliteDataStore.open(dir.resolve("mecc.db"));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("mecc.db"));
             var statement = connection.createStatement()) {
            assertEquals(Migrations.latestVersion(), Migrations.currentVersion(connection));
            var mode = statement.executeQuery("PRAGMA journal_mode");
            mode.next();
            assertEquals("wal", mode.getString(1));
        }
    }

    @Test
    void refusesDatabaseFromNewerVersion() throws Exception {
        store.close();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("mecc.db"));
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO schema_migrations (version, name, applied_at) VALUES (999, 'future', 0)");
        }
        assertThrows(java.sql.SQLException.class, () -> SqliteDataStore.open(dir.resolve("mecc.db")));
        store = SqliteDataStore.open(dir.resolve("other.db"));
    }

    @Test
    void usersAndDevicesRoundTrip() throws Exception {
        Device device = new Device("abcdefghjk", steve.uuid(), "Chrome · Windows", "UA", NOW, NOW, "127.0.0.1", null);
        await(store.write(repos -> {
            repos.users().upsert(steve, NOW);
            repos.users().upsert(new PlayerProfile(steve.uuid(), "SteveRenamed"), NOW);
            repos.devices().insert(device, "hash-1");
            return null;
        }));

        assertEquals("SteveRenamed", await(store.read(repos -> repos.users().find(steve.uuid()))).orElseThrow().playerName());
        assertEquals(steve.uuid(), await(store.read(repos -> repos.users().findByName("steverenamed"))).orElseThrow().playerUuid());
        assertEquals(device, await(store.read(repos -> repos.devices().findActiveByTokenHash("hash-1"))).orElseThrow());

        assertTrue(await(store.<Boolean>write(repos -> repos.devices().revoke(device.id(), NOW))));
        assertTrue(await(store.read(repos -> repos.devices().findActiveByTokenHash("hash-1"))).isEmpty());
        assertFalse(await(store.<Boolean>write(repos -> repos.devices().revoke(device.id(), NOW))));
    }

    @Test
    void failedTransactionRollsBack() throws Exception {
        ExecutionException e = assertThrows(ExecutionException.class, () -> store.write(repos -> {
            repos.users().upsert(steve, NOW);
            throw new IllegalStateException("boom");
        }).get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertTrue(await(store.read(repos -> repos.users().find(steve.uuid()))).isEmpty());
    }

    @Test
    void networksAnchorsAndMembers() throws Exception {
        BlockLocation location = new BlockLocation("minecraft:overworld", 1, 64, 2);
        WebNetwork network = new WebNetwork(UUID.randomUUID(), "Base", steve.uuid(), NOW, null, NetworkRecordStatus.ONLINE,
                List.of(new NetworkAnchor(location, steve.uuid(), NOW)));
        await(store.write(repos -> {
            repos.users().upsert(steve, NOW);
            repos.networks().insert(network);
            repos.networks().upsertMember(new NetworkMember(network.id(), steve.uuid(), NetworkRole.OWNER, null, NOW));
            return null;
        }));

        assertEquals(network, await(store.read(repos -> repos.networks().find(network.id()))).orElseThrow());
        assertEquals(Map.of(network.id(), NetworkRole.OWNER), await(store.read(repos -> repos.networks().membershipsOf(steve.uuid()))));

        WebNetwork duplicate = new WebNetwork(UUID.randomUUID(), "Copy", steve.uuid(), NOW, null, NetworkRecordStatus.ONLINE,
                List.of(new NetworkAnchor(location, steve.uuid(), NOW)));
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> store.write(repos -> {
                    repos.networks().insert(duplicate);
                    return null;
                }).get(5, TimeUnit.SECONDS));
        assertInstanceOf(DuplicateKeyException.class, e.getCause());
        assertEquals(1, await(store.read(repos -> repos.networks().listAll())).size(), "whole insert rolled back");

        UUID other = UUID.randomUUID();
        assertFalse(await(store.<Boolean>write(repos -> repos.networks().addAnchor(other, new NetworkAnchor(location, null, NOW)))));
        await(store.write(repos -> {
            repos.networks().removeAnchor(other, location);
            return null;
        }));
        assertEquals(1, await(store.read(repos -> repos.networks().find(network.id()))).orElseThrow().anchors().size(),
                "removal is scoped to the given network");

        assertTrue(await(store.<Boolean>write(repos -> repos.networks().delete(network.id()))));
        assertTrue(await(store.read(repos -> repos.networks().membershipsOf(steve.uuid()))).isEmpty(), "members cascade");
    }

    @Test
    void auditLogStoresParameters() throws Exception {
        AuditEvent event = new AuditEvent(NOW, steve.uuid(), "dev", null, AuditAction.DEVICE_PAIR, "dev",
                AuditResult.SUCCESS, true, Map.of("name", "Quote \" and \\ and\nnewline", "n", "1"));
        await(store.write(repos -> {
            repos.audit().append(event);
            return null;
        }));
        assertEquals(List.of(event), await(store.read(repos -> repos.audit().recent(10))));
    }

    @Test
    void craftingOrdersPageAndCascade() throws Exception {
        WebNetwork network = new WebNetwork(UUID.randomUUID(), "Base", steve.uuid(), NOW, null, NetworkRecordStatus.ONLINE,
                List.of(new NetworkAnchor(new BlockLocation("minecraft:overworld", 1, 64, 2), steve.uuid(), NOW)));
        OrderTarget target = new OrderTarget(ResourceId.of("fluid", "minecraft", "water"),
                Map.of("en_us", "Water", "zh_cn", "水"), "minecraft", "fluid/minecraft/water", new ResourceUnit("B", 1000));
        List<CraftingOrder> created = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            created.add(new CraftingOrder(UUID.randomUUID(), network.id(), steve.uuid(), "dev", OrderSource.MANUAL, target,
                    1000L * (i + 1), OrderState.SUBMITTING, null, null, null, 64L, NOW.plusSeconds(i), null, null, null,
                    null, null, null));
        }
        await(store.write(repos -> {
            repos.users().upsert(steve, NOW);
            repos.networks().insert(network);
            created.forEach(repos.orders()::insert);
            return null;
        }));

        CraftingOrder running = created.get(0).started("job-1", "cpu-1", "Main CPU", NOW.plusSeconds(5));
        CraftingOrder done = created.get(1).started("job-2", "cpu-1", null, NOW.plusSeconds(5))
                .ended(OrderState.COMPLETED, NOW.plusSeconds(9), null, null);
        await(store.write(repos -> {
            assertTrue(repos.orders().update(running));
            assertTrue(repos.orders().update(done));
            repos.orders().appendEvent(new OrderEvent(done.id(), NOW, OrderEventType.COMPLETED, null, Map.of("k", "v")));
            return null;
        }));

        assertEquals(running, await(store.read(repos -> repos.orders().find(running.id()))).orElseThrow());
        assertEquals(done, await(store.read(repos -> repos.orders().find(done.id()))).orElseThrow());
        List<CraftingOrder> active = await(store.read(repos -> repos.orders().list(network.id(), OrderState.ACTIVE, 10, null)));
        assertEquals(List.of(created.get(2).id(), running.id()), active.stream().map(CraftingOrder::id).toList(), "newest first");
        List<CraftingOrder> page2 = await(store.read(repos -> repos.orders().list(network.id(), OrderState.ACTIVE, 10,
                new CraftingOrderRepository.Cursor(active.get(0).createdAt(), active.get(0).id()))));
        assertEquals(List.of(running.id()), page2.stream().map(CraftingOrder::id).toList());
        assertEquals(2, await(store.read(repos -> repos.orders().listByStates(OrderState.ACTIVE))).size());
        assertEquals(1, await(store.read(repos -> repos.orders().events(done.id()))).size());

        await(store.write(repos -> repos.networks().delete(network.id())));
        assertTrue(await(store.read(repos -> repos.orders().find(running.id()))).isEmpty(), "orders cascade with the network");
    }

    @Test
    void patternDraftsKeepEmptySlotsAndDeploymentsCascade() throws Exception {
        WebNetwork network = new WebNetwork(UUID.randomUUID(), "Base", steve.uuid(), NOW, null, NetworkRecordStatus.ONLINE,
                List.of(new NetworkAnchor(new BlockLocation("minecraft:overworld", 1, 64, 2), steve.uuid(), NOW)));
        PatternStack plank = new PatternStack(ResourceId.of("item", "minecraft", "oak_planks"), 1);
        List<PatternStack> grid = new java.util.ArrayList<>(java.util.Collections.nCopies(9, (PatternStack) null));
        grid.set(0, plank);
        grid.set(3, plank);
        PatternDraft crafting = new PatternDraft(UUID.randomUUID(), steve.uuid(), network.id(), "Sticks", "",
                new PatternDefinition(PatternType.CRAFTING, grid, List.of(), true, false, "minecraft:stick"), NOW, NOW);
        List<PatternStack> processingInputs = new java.util.ArrayList<>();
        processingInputs.add(null);
        processingInputs.add(new PatternStack(ResourceId.of("fluid", "minecraft", "water"), 2000));
        PatternDraft processing = new PatternDraft(UUID.randomUUID(), steve.uuid(), null, "Mud", "wet",
                new PatternDefinition(PatternType.PROCESSING, processingInputs,
                        List.of(new PatternStack(ResourceId.of("item", "minecraft", "mud"), 4)), false, false, null),
                NOW, NOW.plusSeconds(1));
        await(store.write(repos -> {
            repos.users().upsert(steve, NOW);
            repos.networks().insert(network);
            repos.patternDrafts().insert(crafting);
            repos.patternDrafts().insert(processing);
            return null;
        }));

        assertEquals(crafting, await(store.read(repos -> repos.patternDrafts().find(crafting.id()))).orElseThrow(),
                "empty grid cells survive the round trip");
        assertEquals(List.of(processing, crafting), await(store.read(repos -> repos.patternDrafts().listByOwner(steve.uuid()))),
                "most recently updated first");
        assertEquals(2, (int) await(store.read(repos -> repos.patternDrafts().countByOwner(steve.uuid()))));

        PatternDraft renamed = new PatternDraft(processing.id(), steve.uuid(), network.id(), "Mud II", "", new PatternDefinition(
                PatternType.PROCESSING, List.of(plank), List.of(plank), false, false, null), NOW, NOW.plusSeconds(5));
        assertTrue(await(store.<Boolean>write(repos -> repos.patternDrafts().update(renamed))));
        assertEquals(renamed, await(store.read(repos -> repos.patternDrafts().find(processing.id()))).orElseThrow(),
                "update replaces the slots");

        OrderTarget output = new OrderTarget(ResourceId.of("item", "minecraft", "stick"), Map.of("en_us", "Stick"),
                "minecraft", "item/minecraft/stick", null);
        PatternDeployment deployed = new PatternDeployment(UUID.randomUUID(), network.id(), steve.uuid(), "dev", crafting.id(),
                PatternType.CRAFTING, PatternDeployment.Action.DEPLOY, output, "p1", "Assembler", 3, null, NOW);
        PatternDeployment failed = new PatternDeployment(UUID.randomUUID(), network.id(), steve.uuid(), "dev", null,
                PatternType.PROCESSING, PatternDeployment.Action.ENCODE, null, null, null, null, "NO_BLANK_PATTERN",
                NOW.plusSeconds(1));
        await(store.write(repos -> {
            repos.patternDeployments().insert(deployed);
            repos.patternDeployments().insert(failed);
            return null;
        }));
        assertEquals(List.of(failed, deployed), await(store.read(repos -> repos.patternDeployments().recent(network.id(), 10))));

        await(store.write(repos -> repos.networks().delete(network.id())));
        assertTrue(await(store.read(repos -> repos.patternDeployments().recent(network.id(), 10))).isEmpty(),
                "history cascades with the network");
        assertEquals(null, await(store.read(repos -> repos.patternDrafts().find(crafting.id()))).orElseThrow().networkId(),
                "drafts outlive the network they were made for");

        assertTrue(await(store.<Boolean>write(repos -> repos.patternDrafts().delete(crafting.id()))));
        assertEquals(1, (int) await(store.read(repos -> repos.patternDrafts().countByOwner(steve.uuid()))));
    }

    private static <T> T await(java.util.concurrent.CompletableFuture<T> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }
}
