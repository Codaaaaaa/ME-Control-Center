package io.github.codaaaaaa.mecc.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.codaaaaaa.mecc.core.command.ChatReply;
import io.github.codaaaaaa.mecc.core.networks.DiscoverySnapshot.DiscoveredGrid;
import io.github.codaaaaaa.mecc.core.networks.GridStatus;
import io.github.codaaaaaa.mecc.core.users.PlayerProfile;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform.PlanEntry;
import io.github.codaaaaaa.mecc.platform.PatternPlatform;
import java.net.CookieManager;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Milestone 8 (operations features): saved craft orders and alerts, through the real runtime, HTTP server, and
 * database against a fake platform.
 */
class OperationsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String IRON = "item:minecraft:iron_ingot";

    @TempDir
    Path dir;

    private final PlayerProfile steve = new PlayerProfile(UUID.randomUUID(), "Steve");
    private final PlayerProfile alex = new PlayerProfile(UUID.randomUUID(), "Alex");
    private final PlayerProfile eve = new PlayerProfile(UUID.randomUUID(), "Eve");
    private FakePlatform platform;
    private MeccRuntime runtime;
    private int port;
    private Browser owner;
    private String networkId;
    /** Real time plus an offset the tests move forward, so minute-long windows need no waiting. */
    private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.now().plus(offset.get());
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        port = freePort();
        Files.writeString(dir.resolve("mecc.toml"), """
                [web]
                host = "127.0.0.1"
                port = %d
                [networks]
                discovery_interval_seconds = 2
                [resources]
                snapshot_max_age_seconds = 1
                [alerts]
                check_interval_seconds = 300
                """.formatted(port));
        platform = new FakePlatform(dir, Map.of());
        platform.addGrid("grid-steve", steve, 10);
        platform.addResource("item", "minecraft", "iron_ingot", 2_400, true);
        platform.crafting.addCpu("cpu-main", "Main CPU", 65_536);
        platform.crafting.addRecipe("item", "minecraft", "iron_ingot", true, 1_024, List.of(
                new PlanEntry(FakePlatform.descriptor("item", "minecraft", "iron_ingot"), 0, 8, 0)));
        runtime = new MeccRuntime(platform, clock);
        runtime.start();
        runtime.startupFuture().get(30, TimeUnit.SECONDS);
        owner = pair(steve);
        networkId = json(owner.post("/api/v1/networks",
                Map.of("candidateKey", "minecraft:overworld@10,64,0", "displayName", "Base")))
                .path("network").path("id").asText();
    }

    @AfterEach
    void tearDown() {
        runtime.stop();
        platform.close();
    }

    @Test
    void savedOrdersArePersonalPresetsThatRunThroughTheNormalCraftFlow() throws Exception {
        Browser operator = member(alex, "OPERATOR");
        Browser viewer = member(eve, "VIEWER");
        String saved = "/api/v1/networks/" + networkId + "/crafting/saved-orders";

        assertEquals("PERMISSION_DENIED", errorCode(viewer.post(saved, Map.of("name", "Iron", "resourceId", IRON, "amount", 8))));
        assertEquals("VALIDATION_FAILED", errorCode(operator.post(saved, Map.of("name", " ", "resourceId", IRON, "amount", 8))));
        assertEquals("VALIDATION_FAILED", errorCode(operator.post(saved, Map.of("name", "Iron", "resourceId", IRON, "amount", 0))));
        assertEquals("RESOURCE_NOT_FOUND", errorCode(operator.post(saved,
                Map.of("name", "Nope", "resourceId", "item:minecraft:nothing_at_all", "amount", 1))));

        JsonNode preset = json(operator.post(saved, Map.of("name", "Iron batch", "resourceId", IRON, "amount", 64,
                "cpuId", "cpu-main", "notes", "for the smeltery")));
        String presetId = preset.path("id").asText();
        assertEquals(IRON, preset.path("target").path("id").asText());
        assertEquals("cpu-main", preset.path("cpuId").asText());
        json(operator.post(saved, Map.of("name", "Iron batch (copy)", "resourceId", IRON, "amount", 64)));

        JsonNode edited = json(operator.patch(saved + "/" + presetId, Map.of("name", "Iron batch", "amount", 128, "notes", "")));
        assertEquals(128, edited.path("amount").asLong());
        assertTrue(edited.path("cpuId").isNull(), "automatic CPU after editing without one");
        assertEquals(2, json(operator.get(saved)).path("orders").size());
        assertEquals(0, json(owner.get(saved)).path("orders").size(), "presets are personal");
        assertEquals("NOT_FOUND", errorCode(owner.delete(saved + "/" + presetId)), "not someone else's");

        // Running a preset is an ordinary calculate-and-confirm, marked as coming from a saved order.
        JsonNode plan = json(operator.post("/api/v1/networks/" + networkId + "/crafting/plan",
                Map.of("resourceId", IRON, "amount", 128)));
        JsonNode order = json(operator.post("/api/v1/networks/" + networkId + "/crafting/orders",
                Map.of("planId", plan.path("id").asText(), "source", "SAVED_ORDER")));
        assertEquals("SAVED_ORDER", order.path("source").asText());

        assertEquals(204, operator.delete(saved + "/" + presetId).statusCode());
        assertEquals(1, json(operator.get(saved)).path("orders").size());

        // Leaving the network takes the presets along.
        assertEquals(204, owner.delete("/api/v1/networks/" + networkId + "/members/" + alex.uuid()).statusCode());
        member(alex, "OPERATOR");
        assertEquals(0, json(operator.get(saved)).path("orders").size());
    }

    @Test
    void conditionRulesFireOnceResolveAndRespectTheCooldown() throws Exception {
        Browser viewer = member(alex, "VIEWER");
        Browser outsider = pair(eve);

        assertEquals("NETWORK_NOT_FOUND", errorCode(outsider.post("/api/v1/alerts/rules",
                rule("RESOURCE_BELOW", IRON, 100))));
        assertEquals("VALIDATION_FAILED", errorCode(viewer.post("/api/v1/alerts/rules", rule("RESOURCE_BELOW", null, 100))));
        assertEquals("VALIDATION_FAILED", errorCode(viewer.post("/api/v1/alerts/rules", rule("ENERGY_LOW", null, 150))));
        JsonNode low = json(viewer.post("/api/v1/alerts/rules", rule("RESOURCE_BELOW", IRON, 100)));
        assertEquals(30, low.path("cooldownMinutes").asInt(), "condition rules default to a cooldown");
        assertFalse(low.path("active").asBoolean());
        // The fake network sits at 6.25 % energy.
        json(viewer.post("/api/v1/alerts/rules", rule("ENERGY_LOW", null, 10)));

        assertEquals(1, check(), "energy is low; iron is fine");
        assertEquals(0, check(), "a firing rule does not repeat itself");

        setIron(20);
        assertEquals(1, check());
        JsonNode events = json(viewer.get("/api/v1/alerts?networkId=" + networkId)).path("events");
        assertEquals("RESOURCE_BELOW", events.get(0).path("type").asText());
        assertEquals("TRIGGERED", events.get(0).path("kind").asText());
        assertEquals(20, events.get(0).path("value").asLong());
        assertEquals(100, events.get(0).path("threshold").asLong());
        assertEquals("Base", events.get(0).path("networkName").asText());
        assertTrue(ruleById(viewer, low.path("id").asText()).path("active").asBoolean());

        setIron(500);
        assertEquals(1, check());
        assertEquals("RESOLVED", json(viewer.get("/api/v1/alerts")).path("events").get(0).path("kind").asText());

        // Dropping again within the cooldown is remembered, not announced.
        setIron(10);
        assertEquals(0, check());
        assertTrue(ruleById(viewer, low.path("id").asText()).path("active").asBoolean(), "the condition holds");

        // Disabling forgets the state; other players see none of this.
        json(viewer.patch("/api/v1/alerts/rules/" + low.path("id").asText(), Map.of("enabled", false)));
        assertFalse(ruleById(viewer, low.path("id").asText()).path("active").asBoolean());
        assertEquals(0, json(owner.get("/api/v1/alerts")).path("events").size());
        assertEquals("NOT_FOUND", errorCode(owner.delete("/api/v1/alerts/rules/" + low.path("id").asText())));

        // An unloaded network is offline.
        json(viewer.post("/api/v1/alerts/rules", rule("NETWORK_OFFLINE", null, null)));
        assertEquals(0, check());
        DiscoveredGrid grid = platform.grids.get(0);
        platform.grids.clear();
        Thread.sleep(3_000); // Discovery notices.
        assertEquals(1, check());
        assertEquals("NETWORK_OFFLINE", json(viewer.get("/api/v1/alerts")).path("events").get(0).path("type").asText());
        platform.grids.add(new DiscoveredGrid(grid.runtimeKey(), grid.anchors(), new GridStatus(true, false,
                "CONTROLLER_ONLINE", "DEFAULT", 15_000.0, 16_000.0, 1.0, 1.0, 1, 64, 1, 4, 1, null)));
        Thread.sleep(3_000);
        assertEquals(2, check(), "back online, energy recovered");

        // Leaving the network deletes the member's rules.
        assertEquals(204, owner.delete("/api/v1/networks/" + networkId + "/members/" + alex.uuid()).statusCode());
        assertEquals(0, json(viewer.get("/api/v1/alerts")).path("events").size());
    }

    @Test
    void craftRulesCoverTheOwnersOrders() throws Exception {
        Browser operator = member(alex, "OPERATOR");
        json(operator.post("/api/v1/alerts/rules", rule("CRAFT_COMPLETED", null, null)));
        json(owner.post("/api/v1/alerts/rules", rule("CRAFT_COMPLETED", null, null)));

        JsonNode plan = json(operator.post("/api/v1/networks/" + networkId + "/crafting/plan",
                Map.of("resourceId", IRON, "amount", 8)));
        String orderId = json(operator.post("/api/v1/networks/" + networkId + "/crafting/orders",
                Map.of("planId", plan.path("id").asText()))).path("id").asText();
        platform.crafting.complete("cpu-main");

        JsonNode event = null;
        for (int i = 0; i < 100 && event == null; i++) {
            JsonNode events = json(operator.get("/api/v1/alerts")).path("events");
            event = events.isEmpty() ? null : events.get(0);
            if (event == null) {
                Thread.sleep(100);
            }
        }
        assertTrue(event != null, "the order's end raised an alert");
        assertEquals("CRAFT_COMPLETED", event.path("type").asText());
        assertEquals(orderId, event.path("orderId").asText());
        assertEquals(IRON, event.path("resource").path("id").asText());
        assertEquals(0, json(owner.get("/api/v1/alerts")).path("events").size(), "someone else's order");
    }

    @Test
    void percentageRulesCompareWithTheAmountOneWindowAgo() throws Exception {
        Browser viewer = member(alex, "VIEWER");
        assertEquals("VALIDATION_FAILED", errorCode(viewer.post("/api/v1/alerts/rules", rule("RESOURCE_DROP", IRON, 150))));
        Map<String, Object> drop = rule("RESOURCE_DROP", IRON, 50);
        drop.put("windowMinutes", 10);
        drop.put("cooldownMinutes", 0);
        JsonNode created = json(viewer.post("/api/v1/alerts/rules", drop));
        assertEquals(10, created.path("windowMinutes").asInt());

        assertEquals(0, check(), "no history covering the window yet");
        advance(Duration.ofMinutes(11));
        setIron(1_000);
        assertEquals(1, check(), "2400 -> 1000 is a 58 % drop");
        JsonNode event = json(viewer.get("/api/v1/alerts")).path("events").get(0);
        assertEquals("RESOURCE_DROP", event.path("type").asText());
        assertEquals(58, event.path("value").asLong());
        assertEquals(50, event.path("threshold").asLong());

        advance(Duration.ofMinutes(11));
        assertEquals(1, check(), "steady for a whole window: no longer dropping");
        assertEquals("RESOLVED", json(viewer.get("/api/v1/alerts")).path("events").get(0).path("kind").asText());

        setIron(0);
        advance(Duration.ofMinutes(11));
        assertEquals(1, check(), "1000 -> 0");
        advance(Duration.ofMinutes(11));
        setIron(50);
        assertEquals(0, check(), "a window that starts at 0 has no percentage: nothing changes");
    }

    @Test
    void stalledCraftsAreReportedOnceAndResolveWhenProgressMoves() throws Exception {
        Browser operator = member(alex, "OPERATOR");
        Map<String, Object> stall = rule("CRAFT_STALLED", null, 5);
        json(operator.post("/api/v1/alerts/rules", stall));
        JsonNode plan = json(operator.post("/api/v1/networks/" + networkId + "/crafting/plan",
                Map.of("resourceId", IRON, "amount", 8)));
        String orderId = json(operator.post("/api/v1/networks/" + networkId + "/crafting/orders",
                Map.of("planId", plan.path("id").asText()))).path("id").asText();
        platform.crafting.cpu("cpu-main").job.progress = 0.25;
        Thread.sleep(2_500); // The order tracker polls CPUs every 2 s.

        assertEquals(0, check(), "progress seen for the first time");
        advance(Duration.ofMinutes(6));
        assertEquals(1, check());
        JsonNode event = json(operator.get("/api/v1/alerts")).path("events").get(0);
        assertEquals("CRAFT_STALLED", event.path("type").asText());
        assertEquals(orderId, event.path("orderId").asText());
        assertEquals(0, check(), "reported once per stall");
        JsonNode rules = json(operator.get("/api/v1/alerts/rules?networkId=" + networkId)).path("rules");
        assertTrue(rules.get(0).path("active").asBoolean());

        platform.crafting.cpu("cpu-main").job.progress = 0.5;
        Thread.sleep(2_500);
        assertEquals(1, check());
        assertEquals("RESOLVED", json(operator.get("/api/v1/alerts")).path("events").get(0).path("kind").asText());
        assertFalse(json(operator.get("/api/v1/alerts/rules?networkId=" + networkId)).path("rules").get(0)
                .path("active").asBoolean());
    }

    @Test
    void inGameJobsNameTheirRequesterAndStallAlertsCoverThem() throws Exception {
        Browser alexBrowser = member(alex, "OPERATOR");
        String cpus = "/api/v1/networks/" + networkId + "/crafting/cpus";
        FakePlatform.FakeJob job = new FakePlatform.FakeJob("in-game-1",
                FakePlatform.descriptor("item", "minecraft", "iron_ingot"), 64);
        job.requester = alex.uuid();
        job.progress = 0.1;
        platform.crafting.cpu("cpu-main").job = job;
        JsonNode view = json(owner.get(cpus + "?locale=en_us")).path("cpus").get(0).path("job");
        assertEquals("IN_GAME", view.path("origin").asText());
        assertTrue(view.path("paired").asBoolean(), "Alex paired a browser");
        assertEquals("Alex", view.path("initiator").path("playerName").asText());

        job.requester = eve.uuid();
        job.requesterName = "Eve";
        advance(Duration.ofSeconds(5)); // Past the CPU snapshot's age.
        view = json(owner.get(cpus + "?locale=en_us")).path("cpus").get(0).path("job");
        assertFalse(view.path("paired").asBoolean());
        assertEquals("Eve", view.path("initiator").path("playerName").asText(), "the server's name for her");

        job.requester = null;
        job.requesterName = null;
        advance(Duration.ofSeconds(5));
        assertEquals("UNKNOWN", json(owner.get(cpus + "?locale=en_us")).path("cpus").get(0).path("job").path("origin").asText());

        // A job Alex started in game stalls: his stall rule covers it, although there is no order.
        job.requester = alex.uuid();
        json(alexBrowser.post("/api/v1/alerts/rules", rule("CRAFT_STALLED", null, 5)));
        assertEquals(0, check());
        advance(Duration.ofMinutes(6));
        assertEquals(1, check());
        JsonNode event = json(alexBrowser.get("/api/v1/alerts")).path("events").get(0);
        assertEquals("CRAFT_STALLED", event.path("type").asText());
        assertTrue(event.path("orderId").isNull(), "started in game");
    }

    @Test
    void theCraftingTreeShowsWhereEachStepStands() throws Exception {
        String tree = "/api/v1/networks/" + networkId + "/crafting/cpus/cpu-main/tree?locale=en_us";
        assertEquals("NOT_RUNNING", errorCode(owner.get(tree)), "idle CPU");
        var block = FakePlatform.descriptor("item", "minecraft", "iron_block");
        var ingot = FakePlatform.descriptor("item", "minecraft", "iron_ingot");
        var ore = FakePlatform.descriptor("item", "minecraft", "raw_iron");
        FakePlatform.FakeJob job = new FakePlatform.FakeJob("tree-1", block, 2);
        job.tasks = List.of(
                new CraftingPlatform.JobTask("t-block", List.of(new CraftingPlatform.ResourceAmount(block, 1)),
                        List.of(new CraftingPlatform.ResourceAmount(ingot, 9)), 2, true, List.of("m-press")),
                new CraftingPlatform.JobTask("t-ingot", List.of(new CraftingPlatform.ResourceAmount(ingot, 1)),
                        List.of(new CraftingPlatform.ResourceAmount(ore, 1)), 18, true, List.of()));
        job.stored = List.of(new CraftingPlatform.ResourceAmount(ore, 12));
        job.inMachines = List.of(new CraftingPlatform.ResourceAmount(ingot, 6));
        platform.crafting.cpu("cpu-main").job = job;

        JsonNode view = json(member(eve, "VIEWER").get(tree));
        assertEquals("t-block", view.path("root").path("id").asText());
        assertEquals("WAITING_INPUTS", view.path("root").path("status").asText());
        JsonNode ingots = view.path("root").path("children").get(0);
        assertEquals("CRAFTING", ingots.path("status").asText());
        assertEquals(6, ingots.path("inMachines").asLong());
        assertEquals("FROM_STORAGE", ingots.path("children").get(0).path("status").asText());
        assertEquals(1, view.path("counts").path("CRAFTING").asInt());
    }

    @Test
    void machinesThatAJobWaitsForAndDoNotChangeAreStuck() throws Exception {
        var furnace = FakePlatform.descriptor("item", "minecraft", "furnace");
        platform.patterns.machines.add(new PatternPlatform.MachineState("m-furnace", furnace, null, List.of("p1"), true, 0,
                true, 42, null, null, null));
        String machines = "/api/v1/networks/" + networkId + "/machines?locale=en_us";
        JsonNode first = json(owner.get(machines)).path("machines").get(0);
        assertEquals("WORKING", first.path("status").asText(), "just seen");
        assertEquals("minecraft:furnace", first.path("block").path("id").asText().replace("item:", ""));

        json(owner.post("/api/v1/alerts/rules", rule("MACHINE_STUCK", null, 2)));
        advance(Duration.ofMinutes(3));
        JsonNode stuck = json(owner.get(machines)).path("machines").get(0);
        assertEquals("STUCK", stuck.path("status").asText());
        assertEquals("NO_CHANGE", stuck.path("reason").asText());
        assertEquals(1, check());
        assertEquals("MACHINE_STUCK", json(owner.get("/api/v1/alerts")).path("events").get(0).path("type").asText());

        platform.patterns.machines.set(0, new PatternPlatform.MachineState("m-furnace", furnace, null, List.of("p1"), true,
                0, true, 43, null, null, null));
        advance(Duration.ofSeconds(10));
        assertEquals(1, check(), "it moved: resolved");
        assertEquals("RESOLVED", json(owner.get("/api/v1/alerts")).path("events").get(0).path("kind").asText());
    }

    @Test
    void machinesThatReportTheirOwnStateAreNotGuessedAt() throws Exception {
        var block = FakePlatform.descriptor("item", "gtceu", "electric_blast_furnace");
        // A crafting job waits for what it makes and it has not moved for an hour: the guess would call it stuck.
        platform.patterns.machines.add(new PatternPlatform.MachineState("m-gt", block, null, List.of("p1"), true, 0,
                false, 42, "IDLE", null, null));
        String machines = "/api/v1/networks/" + networkId + "/machines?locale=en_us";
        json(owner.post("/api/v1/alerts/rules", rule("MACHINE_STUCK", null, 2)));
        advance(Duration.ofHours(1));
        JsonNode idle = json(owner.get(machines)).path("machines").get(0);
        assertEquals("IDLE", idle.path("status").asText(), "it says it has nothing to make");
        assertTrue(idle.path("reason").isNull());
        assertEquals(0, check(), "nothing to announce");

        platform.patterns.machines.set(0, new PatternPlatform.MachineState("m-gt", block, null, List.of("p1"), false, 0,
                false, 42, "SUSPEND", null, null));
        advance(Duration.ofHours(1));
        assertEquals("DISABLED", json(owner.get(machines)).path("machines").get(0).path("status").asText());
        assertEquals(0, check(), "switched off on purpose");

        platform.patterns.machines.set(0, new PatternPlatform.MachineState("m-gt", block, null, List.of("p1"), false, 0,
                false, 42, "WAITING", null, "Not enough energy"));
        advance(Duration.ofSeconds(10));
        assertEquals("WORKING", json(owner.get(machines)).path("machines").get(0).path("status").asText(),
                "it only just said so");
        advance(Duration.ofMinutes(3));
        JsonNode stuck = json(owner.get(machines)).path("machines").get(0);
        assertEquals("STUCK", stuck.path("status").asText(), "no job has to wait for it to be stuck");
        assertEquals("MACHINE_WAITING", stuck.path("reason").asText());
        assertEquals("Not enough energy", stuck.path("waitingReason").asText(), "in the machine's own words");
        assertEquals(1, check());
    }

    private void advance(Duration duration) {
        offset.updateAndGet(current -> current.plus(duration));
    }

    @Test
    void webhooksCannotReachTheServersOwnNetwork() throws Exception {
        assertEquals("VALIDATION_FAILED", errorCode(owner.patch("/api/v1/alerts/settings",
                Map.of("discordWebhookUrl", "https://example.org/api/webhooks/1/x"))), "Discord webhooks point at Discord");
        assertEquals("VALIDATION_FAILED", errorCode(owner.patch("/api/v1/alerts/settings",
                Map.of("webhookUrl", "ftp://example.org/hook"))));
        assertEquals("VALIDATION_FAILED", errorCode(owner.patch("/api/v1/alerts/settings",
                Map.of("webhookUrl", "https://user:secret@example.org/hook"))));

        JsonNode settings = json(owner.patch("/api/v1/alerts/settings",
                Map.of("discordWebhookUrl", "https://discord.com/api/webhooks/1/abc", "webhookUrl",
                        "http://127.0.0.1:" + port + "/api/v1/status", "locale", "zh_cn")));
        assertEquals("https://discord.com/api/webhooks/1/abc", settings.path("discordWebhookUrl").asText());
        assertTrue(settings.path("webhooksEnabled").asBoolean());

        // The check happens when sending, after resolving: loopback is refused before any request is made.
        JsonNode result = json(owner.post("/api/v1/alerts/test", Map.of()));
        assertTrue(result.path("channels").path("webhook").asText().contains("internal address"),
                result.toString());
    }

    private Map<String, Object> rule(String type, String resourceId, Integer threshold) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("networkId", networkId);
        body.put("type", type);
        body.put("resourceId", resourceId);
        body.put("threshold", threshold);
        return body;
    }

    private JsonNode ruleById(Browser browser, String id) throws Exception {
        for (JsonNode rule : json(browser.get("/api/v1/alerts/rules?networkId=" + networkId)).path("rules")) {
            if (rule.path("id").asText().equals(id)) {
                return rule;
            }
        }
        throw new AssertionError("no rule " + id);
    }

    private int check() throws Exception {
        Thread.sleep(1_100); // Let the cached storage snapshot expire.
        return runtime.alertMonitor().checkOnce().get(10, TimeUnit.SECONDS);
    }

    private void setIron(long amount) {
        platform.storage.clear();
        platform.addResource("item", "minecraft", "iron_ingot", amount, true);
    }

    private Browser member(PlayerProfile player, String role) throws Exception {
        Browser browser = pair(player);
        json(owner.post("/api/v1/networks/" + networkId + "/members", Map.of("player", player.name(), "role", role)));
        return browser;
    }

    private Browser pair(PlayerProfile player) throws Exception {
        ChatReply reply = runtime.commands().pair(player, "en_us");
        String key = reply.lines().stream().flatMap(line -> line.spans().stream())
                .filter(span -> span.style() == ChatReply.Style.SECRET)
                .findFirst().orElseThrow().text();
        Browser browser = new Browser();
        json(browser.post("/api/v1/auth/pair", Map.of("key", key)));
        return browser;
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        assertTrue(response.statusCode() < 300, response.statusCode() + " " + response.body());
        return JSON.readTree(response.body());
    }

    private static String errorCode(HttpResponse<String> response) throws Exception {
        assertTrue(response.statusCode() >= 400, response.statusCode() + " " + response.body());
        return JSON.readTree(response.body()).path("error").path("code").asText();
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private final class Browser {
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

        HttpResponse<String> get(String path) throws Exception {
            return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> delete(String path) throws Exception {
            return client.send(request(path).DELETE().build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> post(String path, Object body) throws Exception {
            return send("POST", path, body);
        }

        HttpResponse<String> patch(String path, Object body) throws Exception {
            return send("PATCH", path, body);
        }

        private HttpResponse<String> send(String method, String path, Object body) throws Exception {
            return client.send(request(path).header("Content-Type", "application/json")
                            .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        private HttpRequest.Builder request(String path) {
            return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header("Origin", "http://127.0.0.1:" + port);
        }
    }
}
