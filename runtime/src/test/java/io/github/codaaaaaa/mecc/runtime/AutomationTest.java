package io.github.codaaaaaa.mecc.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.codaaaaaa.mecc.core.command.ChatReply;
import io.github.codaaaaaa.mecc.core.networks.BlockLocation;
import io.github.codaaaaaa.mecc.core.resources.ResourceText;
import io.github.codaaaaaa.mecc.core.users.PlayerProfile;
import io.github.codaaaaaa.mecc.platform.NetworkPlatform.RequestState;
import io.github.codaaaaaa.mecc.platform.NetworkPlatform.RequesterState;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform.PlanEntry;
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
import java.util.HashMap;
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
 * Milestone 9 (advanced automation): Keep Stock rules with their safety limits, the automation audit trail, and the
 * Network Explorer, through the real runtime, HTTP server, and database against a fake platform.
 */
class AutomationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String IRON = "item:minecraft:iron_ingot";

    @TempDir
    Path dir;

    private final PlayerProfile steve = new PlayerProfile(UUID.randomUUID(), "Steve");
    private final PlayerProfile alex = new PlayerProfile(UUID.randomUUID(), "Alex");
    private FakePlatform platform;
    private MeccRuntime runtime;
    private int port;
    private Browser owner;
    private String networkId;
    private String restock;
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
                enabled = false
                [automation]
                auto_restock_enabled = true
                check_interval_seconds = 3600
                max_active_jobs_per_network = 1
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
        restock = "/api/v1/networks/" + networkId + "/automation/restock";
    }

    @AfterEach
    void tearDown() {
        runtime.stop();
        platform.close();
    }

    @Test
    void keepStockCraftsBackUpToTheTargetAndRespectsItsSafetyLimits() throws Exception {
        Browser operator = member(alex, "OPERATOR");
        assertEquals("PERMISSION_DENIED", errorCode(operator.post(restock, rule(IRON, 1_000L, 4_096L))),
                "Operators may craft, but not automate");
        assertEquals("VALIDATION_FAILED", errorCode(owner.post(restock, rule(IRON, 4_096L, 1_000L))),
                "the target must be above the minimum");
        assertEquals("RESOURCE_NOT_FOUND", errorCode(owner.post(restock, rule("item:minecraft:nothing", 1L, 2L))));

        JsonNode created = json(owner.post(restock, rule(IRON, 1_000L, 4_096L)));
        String ruleId = created.path("id").asText();
        assertFalse(created.path("enabled").asBoolean(), "a new rule is off until it is switched on");
        assertEquals(2_400, created.path("stored").asLong());
        assertEquals("CONFLICT", errorCode(owner.post(restock, rule(IRON, 1L, 2L))), "one rule per resource");
        assertEquals(0, run(), "a disabled rule never submits");

        json(owner.patch(restock + "/" + ruleId, Map.of("enabled", true, "cooldownMinutes", 30)));
        assertEquals(0, run(), "2400 is above the minimum");

        setIron(500);
        assertEquals(1, run(), "below 1000: craft back up to 4096");
        JsonNode order = json(owner.get("/api/v1/networks/" + networkId + "/crafting/orders")).path("orders").get(0);
        assertEquals("AUTOMATION", order.path("source").asText());
        assertEquals(3_596, order.path("amount").asLong(), "4096 - 500");
        assertEquals("Steve", order.path("creator").path("playerName").asText());

        assertEquals(0, run(), "an order for this resource is already running");
        platform.crafting.complete("cpu-main");
        Thread.sleep(2_500); // The order tracker notices the job is gone.
        assertEquals(0, run(), "still within the cooldown");

        advance(Duration.ofMinutes(31));
        assertEquals(1, run());
        assertEquals(2, json(owner.get("/api/v1/networks/" + networkId + "/crafting/orders?status=ALL"))
                .path("orders").size());

        // The audit log records what automation did (spec section 25: automation audit trail).
        JsonNode audit = json(owner.get("/api/v1/networks/" + networkId + "/audit")).path("entries");
        assertTrue(entryOf(audit, "RESTOCK_CRAFT") != null, audit.toString());
        assertTrue(entryOf(audit, "RESTOCK_RULE_CHANGE") != null, audit.toString());

        // Kill switch: everything off at once, and that is audited too.
        platform.crafting.complete("cpu-main");
        Thread.sleep(2_500);
        advance(Duration.ofMinutes(31));
        assertEquals(1, json(owner.post("/api/v1/networks/" + networkId + "/automation/stop", Map.of()))
                .path("rules").size());
        assertFalse(json(owner.get(restock)).path("rules").get(0).path("enabled").asBoolean());
        assertEquals(0, run(), "the kill switch stopped it");
        assertTrue(entryOf(json(owner.get("/api/v1/networks/" + networkId + "/audit")).path("entries"),
                "AUTOMATION_STOPPED") != null);

        assertEquals(204, owner.delete(restock + "/" + ruleId).statusCode());
        assertEquals(0, json(owner.get(restock)).path("rules").size());
    }

    @Test
    void aRuleThatKeepsFailingBacksOffAndTurnsItselfOff() throws Exception {
        String ruleId = json(owner.post(restock, rule(IRON, 1_000L, 4_096L))).path("id").asText();
        json(owner.patch(restock + "/" + ruleId, Map.of("enabled", true, "cooldownMinutes", 1)));
        setIron(500);

        for (int attempt = 1; attempt <= 5; attempt++) {
            platform.crafting.rejectNext = "NO_SUITABLE_CPU";
            assertEquals(0, run(), "the crafting system refused the job");
            JsonNode rule = json(owner.get(restock)).path("rules").get(0);
            assertEquals(attempt, rule.path("failures").asInt());
            assertEquals(attempt < 5, rule.path("enabled").asBoolean(), "off after five failures in a row");
            // Past the exponential backoff (cooldown doubled per failure).
            advance(Duration.ofMinutes(60));
        }
        assertEquals(0, run(), "a disabled rule stays off until someone edits it");
        json(owner.patch(restock + "/" + ruleId, Map.of("enabled", true)));
        assertEquals(0, json(owner.get(restock)).path("rules").get(0).path("failures").asInt(),
                "editing clears the failure backoff");
    }

    @Test
    void theExplorerShowsDevicesChannelsAndWhatIsOffline() throws Exception {
        JsonNode map = json(member(alex, "VIEWER").get("/api/v1/networks/" + networkId + "/explorer?locale=en_us"));
        assertEquals(1, map.path("nodes").asInt());
        assertEquals(0, map.path("offlineNodes").asInt());
        assertTrue(map.path("status").path("powered").asBoolean());
        JsonNode group = map.path("devices").get(0);
        assertEquals("ACCESS_POINT", group.path("kind").asText());
        assertEquals(1, group.path("count").asInt());
        assertEquals(1, group.path("locations").size());
    }

    @Test
    void inGameRequestersAreShownAndAManagerMayEmptyOneOfTheirRequests() throws Exception {
        String requesters = "/api/v1/networks/" + networkId + "/automation/requesters";
        assertEquals(0, json(owner.get(requesters + "?locale=en_us")).path("requesters").size());

        platform.requesters.add(new RequesterState("r1", ResourceText.literal("Iron keeper"),
                new BlockLocation("minecraft:overworld", 1, 2, 3), true,
                List.of(new RequestState(0, FakePlatform.descriptor("item", "minecraft", "iron_ingot"), 64, 16, true,
                        "IDLE", 40L))));

        Browser viewer = member(alex, "VIEWER");
        JsonNode requester = json(viewer.get(requesters + "?locale=en_us")).path("requesters").get(0);
        assertEquals("Iron keeper", requester.path("name").asText());
        assertTrue(requester.path("online").asBoolean());
        JsonNode request = requester.path("requests").get(0);
        assertEquals(64, request.path("amount").asLong());
        assertEquals(16, request.path("batch").asLong());
        assertEquals(40, request.path("stored").asLong());
        assertEquals("IDLE", request.path("status").asText());

        assertEquals("PERMISSION_DENIED", errorCode(viewer.delete(requesters + "/r1/0")),
                "emptying a requester spends the network's resources: Managers only");
        assertEquals(204, owner.delete(requesters + "/r1/0").statusCode());
        assertEquals(0, json(owner.get(requesters + "?locale=en_us")).path("requesters").get(0).path("requests").size());
        assertEquals("NOT_FOUND", errorCode(owner.delete(requesters + "/r1/0")), "it is already gone");
        assertTrue(entryOf(json(owner.get("/api/v1/networks/" + networkId + "/audit")).path("entries"),
                "REQUESTER_REQUEST_CLEARED") != null);
    }

    // --- helpers ------------------------------------------------------------------------------------

    private static JsonNode entryOf(JsonNode entries, String action) {
        for (JsonNode entry : entries) {
            if (entry.path("action").asText().equals(action)) {
                return entry;
            }
        }
        return null;
    }

    private Map<String, Object> rule(String resourceId, Long minimum, Long restockTo) {
        Map<String, Object> body = new HashMap<>();
        body.put("resourceId", resourceId);
        body.put("minimum", minimum);
        body.put("restockTo", restockTo);
        return body;
    }

    private int run() throws Exception {
        Thread.sleep(1_100); // Let the cached storage snapshot expire.
        return runtime.restockEngine().runOnce().get(30, TimeUnit.SECONDS);
    }

    private void advance(Duration duration) {
        offset.updateAndGet(current -> current.plus(duration));
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
