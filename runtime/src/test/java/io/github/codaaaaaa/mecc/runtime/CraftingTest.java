package io.github.codaaaaaa.mecc.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.codaaaaaa.mecc.core.command.ChatReply;
import io.github.codaaaaaa.mecc.core.users.PlayerProfile;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform.PlanEntry;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Milestone 3 exit criterion: a user with Operator permission can safely submit and monitor autocrafting from
 * the browser. Runs the real runtime, HTTP/WebSocket server, database, and order tracker against a fake
 * Minecraft platform.
 */
class CraftingTest {
    private static final ObjectMapper JSON = new ObjectMapper();

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

    @BeforeEach
    void setUp() throws Exception {
        port = freePort();
        Files.writeString(dir.resolve("mecc.toml"), """
                [web]
                host = "127.0.0.1"
                port = %d
                [networks]
                discovery_interval_seconds = 2
                [crafting]
                max_craft_amount = 100000
                """.formatted(port));
        platform = new FakePlatform(dir, Map.of());
        platform.addGrid("grid-steve", steve, 10);
        platform.crafting.addCpu("cpu-main", "Main CPU", 65_536);
        platform.crafting.addCpu("cpu-small", null, 1_024);
        platform.crafting.addRecipe("item", "minecraft", "iron_block", true, 4_096, List.of(
                new PlanEntry(FakePlatform.descriptor("item", "minecraft", "iron_ingot"), 72, 0, 0),
                new PlanEntry(FakePlatform.descriptor("item", "minecraft", "iron_block"), 0, 8, 0)));
        platform.crafting.addRecipe("item", "minecraft", "diamond_block", false, 4_096, List.of(
                new PlanEntry(FakePlatform.descriptor("item", "minecraft", "diamond"), 0, 0, 72)));

        runtime = new MeccRuntime(platform);
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
    void operatorPlansSubmitsAndSeesCompletion() throws Exception {
        Browser operator = member(alex, "OPERATOR");

        JsonNode plan = json(operator.post(crafting("/plan"), Map.of("resourceId", "item:minecraft:iron_block", "amount", 8,
                "locale", "en_us")));
        assertEquals("READY", plan.path("state").asText());
        assertTrue(plan.path("complete").asBoolean());
        assertEquals(4_096, plan.path("bytes").asLong());
        assertEquals("item:minecraft:iron_block", plan.path("output").path("id").asText());
        assertEquals(2, plan.path("entries").size());
        JsonNode small = cpu(plan.path("cpus"), "cpu-small");
        assertEquals("TOO_SMALL", small.path("reason").asText(), "CPU suitability is explained");
        assertTrue(cpu(plan.path("cpus"), "cpu-main").path("reason").isNull());
        assertEquals(0, platform.crafting.submissions.get(), "calculating never submits");

        JsonNode order = json(operator.post(crafting("/orders"), Map.of("planId", plan.path("id").asText())));
        assertEquals("RUNNING", order.path("state").asText());
        assertEquals("cpu-main", order.path("cpu").path("id").asText());
        assertEquals("Main CPU", order.path("cpu").path("name").asText());
        assertTrue(order.path("cancellable").asBoolean(), "operators may cancel their own crafts");
        String orderId = order.path("id").asText();

        assertEquals("PLAN_NOT_FOUND", errorCode(operator.post(crafting("/orders"), Map.of("planId", plan.path("id").asText()))),
                "a plan is submitted at most once");

        platform.crafting.cpu("cpu-main").job.progress = 0.25;
        JsonNode cpus = json(owner.get(crafting("/cpus?locale=en_us")));
        JsonNode job = cpu(cpus.path("cpus"), "cpu-main").path("job");
        assertEquals(orderId, job.path("orderId").asText());
        assertEquals("Alex", job.path("initiator").path("playerName").asText());
        assertEquals(25.0, job.path("progress").path("percent").asDouble(), 0.001);
        assertEquals("AUTHORITATIVE", job.path("progress").path("confidence").asText());
        assertTrue(job.path("cancellable").asBoolean(), "the owner may cancel anyone's craft");
        assertTrue(cpu(cpus.path("cpus"), "cpu-small").path("job").isNull());

        JsonNode active = json(operator.get(crafting("/orders?status=ACTIVE")));
        assertEquals(orderId, active.path("orders").get(0).path("id").asText());

        platform.crafting.complete("cpu-main");
        JsonNode completed = waitFor(() -> json(operator.get(crafting("/orders/" + orderId))).path("order"),
                node -> node.path("state").asText().equals("COMPLETED"));
        assertEquals(100.0, completed.path("progress").path("percent").asDouble(), 0.001);
        assertFalse(completed.path("cancellable").asBoolean());
        assertEquals(1, json(operator.get(crafting("/orders?status=COMPLETED"))).path("orders").size());
        assertEquals(0, json(operator.get(crafting("/orders?status=ACTIVE"))).path("orders").size());
        JsonNode events = json(operator.get(crafting("/orders/" + orderId))).path("events");
        assertEquals(List.of("CREATED", "STARTED", "COMPLETED"), texts(events, "type"));
    }

    @Test
    void permissionsAreCheckedOnEveryCraftingRequest() throws Exception {
        Browser viewer = member(alex, "VIEWER");
        Browser outsider = pair(eve);
        Map<String, Object> request = Map.of("resourceId", "item:minecraft:iron_block", "amount", 1);

        assertEquals("PERMISSION_DENIED", errorCode(viewer.post(crafting("/plan"), request)));
        assertEquals("NETWORK_NOT_FOUND", errorCode(outsider.post(crafting("/plan"), request)));
        assertEquals("NETWORK_NOT_FOUND", errorCode(outsider.get(crafting("/cpus"))));
        assertEquals("NETWORK_NOT_FOUND", errorCode(outsider.get(crafting("/orders"))));
        assertEquals(200, viewer.get(crafting("/cpus")).statusCode(), "viewers can watch CPUs");

        JsonNode plan = json(owner.post(crafting("/plan"), request));
        assertEquals("PERMISSION_DENIED", errorCode(viewer.post(crafting("/orders"), Map.of("planId", plan.path("id").asText()))));
        Browser operator = member(eve, "OPERATOR");
        assertEquals("PLAN_NOT_FOUND", errorCode(operator.post(crafting("/orders"), Map.of("planId", plan.path("id").asText()))),
                "plans belong to whoever calculated them");

        assertEquals("VALIDATION_FAILED", errorCode(owner.post(crafting("/plan"),
                Map.of("resourceId", "item:minecraft:iron_block", "amount", 100_001))), "max_craft_amount applies");
        assertEquals("VALIDATION_FAILED", errorCode(owner.post(crafting("/plan"),
                Map.of("resourceId", "item:minecraft:iron_block", "amount", 0))));
        assertEquals("NOT_CRAFTABLE", errorCode(owner.post(crafting("/plan"),
                Map.of("resourceId", "item:minecraft:dirt", "amount", 1))));
        assertEquals(0, platform.crafting.submissions.get());
    }

    @Test
    void cancellingRespectsOwnershipAndIsAudited() throws Exception {
        Browser alexOperator = member(alex, "OPERATOR");
        Browser eveOperator = member(eve, "OPERATOR");
        String orderId = submit(alexOperator, "item:minecraft:iron_block", 4).path("id").asText();

        JsonNode seenByEve = json(eveOperator.get(crafting("/orders/" + orderId))).path("order");
        assertFalse(seenByEve.path("cancellable").asBoolean());
        assertEquals("PERMISSION_DENIED", errorCode(eveOperator.post(crafting("/orders/" + orderId + "/cancel"), Map.of())));
        assertEquals("PERMISSION_DENIED", errorCode(eveOperator.post(crafting("/cpus/cpu-main/cancel"), Map.of())));
        assertTrue(platform.crafting.cpu("cpu-main").job != null, "nothing was cancelled");

        JsonNode cancelled = json(alexOperator.post(crafting("/orders/" + orderId + "/cancel"), Map.of()));
        assertEquals("CANCELLED", cancelled.path("state").asText());
        assertEquals(null, platform.crafting.cpu("cpu-main").job);
        assertEquals("NOT_RUNNING", errorCode(alexOperator.post(crafting("/orders/" + orderId + "/cancel"), Map.of())));
        JsonNode events = json(alexOperator.get(crafting("/orders/" + orderId))).path("events");
        assertEquals("Alex", events.get(events.size() - 1).path("actor").path("playerName").asText());

        // A job started in game is only cancellable by managers and owners, and only the job that was seen.
        String inGame = submit(owner, "item:minecraft:iron_block", 2).path("id").asText();
        String jobId = json(owner.get(crafting("/cpus"))).path("cpus").get(0).path("job").path("jobId").asText();
        assertEquals("NOT_RUNNING", errorCode(owner.post(crafting("/cpus/cpu-main/cancel"), Map.of("jobId", "other-job"))));
        assertEquals(204, owner.post(crafting("/cpus/cpu-main/cancel"), Map.of("jobId", jobId)).statusCode());
        assertEquals("CANCELLED", json(owner.get(crafting("/orders/" + inGame))).path("order").path("state").asText(),
                "cancelling a CPU running an ME Control Center order cancels the order");
    }

    @Test
    void jobsCancelledInGameAndRejectedSubmissionsAreRecorded() throws Exception {
        String running = submit(owner, "item:minecraft:iron_block", 1).path("id").asText();
        platform.crafting.cancelInGame("cpu-main");
        waitFor(() -> json(owner.get(crafting("/orders/" + running))).path("order"),
                node -> node.path("state").asText().equals("CANCELLED"));

        JsonNode incomplete = json(owner.post(crafting("/plan"), Map.of("resourceId", "item:minecraft:diamond_block", "amount", 1)));
        assertFalse(incomplete.path("complete").asBoolean());
        assertEquals(72, incomplete.path("entries").get(0).path("missing").asLong());
        assertEquals("PLAN_INCOMPLETE", errorCode(owner.post(crafting("/orders"), Map.of("planId", incomplete.path("id").asText()))));

        platform.crafting.rejectNext = "NO_SUITABLE_CPU";
        JsonNode plan = json(owner.post(crafting("/plan"), Map.of("resourceId", "item:minecraft:iron_block", "amount", 1)));
        HttpResponse<String> rejected = owner.post(crafting("/orders"), Map.of("planId", plan.path("id").asText()));
        assertEquals("NO_SUITABLE_CPU", errorCode(rejected));
        String failedId = JSON.readTree(rejected.body()).path("error").path("details").path("orderId").asText();
        JsonNode failed = json(owner.get(crafting("/orders?status=FAILED"))).path("orders").get(0);
        assertEquals(failedId, failed.path("id").asText());
        assertEquals("NO_SUITABLE_CPU", failed.path("failure").path("code").asText());
        assertFalse(failed.path("failure").path("message").asText().isEmpty());

        // The plan survives a rejection, so the user can retry with another CPU.
        assertEquals("RUNNING", json(owner.post(crafting("/orders"),
                Map.of("planId", plan.path("id").asText(), "cpuId", "cpu-main"))).path("state").asText());
    }

    @Test
    void slowCalculationsArePolled() throws Exception {
        platform.crafting.holdCalculations = true;
        JsonNode plan = json(owner.post(crafting("/plan"), Map.of("resourceId", "item:minecraft:iron_block", "amount", 1)));
        assertEquals("CALCULATING", plan.path("state").asText());
        String planId = plan.path("id").asText();
        assertEquals("PLAN_NOT_READY", errorCode(owner.post(crafting("/orders"), Map.of("planId", planId))));
        platform.crafting.holdCalculations = false;
        JsonNode ready = waitFor(() -> json(owner.get(crafting("/plans/" + planId))), node -> node.path("state").asText().equals("READY"));
        assertEquals(4_096, ready.path("bytes").asLong());
    }

    @Test
    void webSocketPushesCpuAndOrderUpdates() throws Exception {
        Browser operator = member(alex, "OPERATOR");
        BlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();
        WebSocket socket = operator.webSocket(events, "http://127.0.0.1:" + port);
        next(events, "session.ready");
        socket.sendText(JSON.writeValueAsString(Map.of("type", "subscribe", "networkId", networkId, "locale", "en_us")), true).join();
        next(events, "subscribed");

        String orderId = submit(operator, "item:minecraft:iron_block", 8).path("id").asText();
        assertEquals(orderId, next(events, "crafting.order.created").path("payload").path("id").asText());
        JsonNode busy = waitForEvent(events, "cpu.updated",
                event -> !cpu(event.path("payload").path("cpus"), "cpu-main").path("job").isNull());
        assertEquals(orderId, cpu(busy.path("payload").path("cpus"), "cpu-main").path("job").path("orderId").asText());

        platform.crafting.complete("cpu-main");
        JsonNode completed = next(events, "crafting.order.completed");
        assertEquals("COMPLETED", completed.path("payload").path("state").asText());
        assertEquals(networkId, completed.path("networkId").asText());

        socket.sendText("{\"type\":\"subscribe\",\"networkId\":\"" + UUID.randomUUID() + "\"}", true).join();
        assertEquals("NETWORK_NOT_FOUND", next(events, "error").path("payload").path("error").path("code").asText());
        socket.abort();
    }

    @Test
    void webSocketRejectsForeignOriginsAndUnpairedBrowsers() throws Exception {
        Browser operator = member(alex, "OPERATOR");
        ExecutionException foreign = assertThrows(ExecutionException.class,
                () -> operator.webSocket(new LinkedBlockingQueue<>(), "https://evil.example"));
        assertNotNull(foreign.getCause());
        ExecutionException anonymous = assertThrows(ExecutionException.class,
                () -> new Browser().webSocket(new LinkedBlockingQueue<>(), "http://127.0.0.1:" + port));
        assertNotNull(anonymous.getCause());
    }

    // --- helpers ------------------------------------------------------------------------------------

    private JsonNode submit(Browser browser, String resourceId, long amount) throws Exception {
        JsonNode plan = json(browser.post(crafting("/plan"), Map.of("resourceId", resourceId, "amount", amount)));
        return json(browser.post(crafting("/orders"), Map.of("planId", plan.path("id").asText())));
    }

    private Browser member(PlayerProfile player, String role) throws Exception {
        Browser browser = pair(player);
        json(owner.post("/api/v1/networks/" + networkId + "/members", Map.of("player", player.name(), "role", role)));
        return browser;
    }

    private Browser pair(PlayerProfile player) throws Exception {
        Browser browser = new Browser();
        json(browser.post("/api/v1/auth/pair", Map.of("key", pairingKey(player))));
        return browser;
    }

    private String crafting(String path) {
        return "/api/v1/networks/" + networkId + "/crafting" + path;
    }

    private String pairingKey(PlayerProfile player) {
        ChatReply reply = runtime.commands().pair(player, "en_us");
        return reply.lines().stream().flatMap(line -> line.spans().stream())
                .filter(span -> span.style() == ChatReply.Style.SECRET)
                .findFirst().orElseThrow().text();
    }

    private static JsonNode cpu(JsonNode cpus, String id) {
        for (JsonNode cpu : cpus) {
            if (cpu.path("id").asText().equals(id)) {
                return cpu;
            }
        }
        throw new AssertionError("No CPU " + id + " in " + cpus);
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> values = new java.util.ArrayList<>();
        array.forEach(node -> values.add(node.path(field).asText()));
        return values;
    }

    @FunctionalInterface
    private interface Read {
        JsonNode get() throws Exception;
    }

    private static JsonNode waitFor(Read read, Predicate<JsonNode> done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        JsonNode last;
        do {
            last = read.get();
            if (done.test(last)) {
                return last;
            }
            Thread.sleep(200);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Condition not reached; last value " + last);
    }

    private static JsonNode next(BlockingQueue<JsonNode> events, String type) throws Exception {
        return waitForEvent(events, type, event -> true);
    }

    private static JsonNode waitForEvent(BlockingQueue<JsonNode> events, String type, Predicate<JsonNode> match) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            JsonNode event = events.poll(200, TimeUnit.MILLISECONDS);
            if (event != null && event.path("type").asText().equals(type) && match.test(event)) {
                return event;
            }
        }
        throw new AssertionError("No " + type + " event arrived");
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
        private final CookieManager cookies = new CookieManager();
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();

        HttpResponse<String> get(String path) throws Exception {
            return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> post(String path, Object body) throws Exception {
            return client.send(request(path).header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        WebSocket webSocket(BlockingQueue<JsonNode> events, String origin) throws Exception {
            WebSocket.Builder builder = client.newWebSocketBuilder().header("Origin", origin);
            for (HttpCookie cookie : cookies.getCookieStore().getCookies()) {
                builder.header("Cookie", cookie.getName() + "=" + cookie.getValue());
            }
            return builder.buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/v1"), new WebSocket.Listener() {
                private final StringBuilder text = new StringBuilder();

                @Override
                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    text.append(data);
                    if (last) {
                        try {
                            events.add(JSON.readTree(text.toString()));
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                        text.setLength(0);
                    }
                    webSocket.request(1);
                    return null;
                }
            }).exceptionally(error -> {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                if (cause instanceof java.net.http.WebSocketHandshakeException handshake) {
                    throw new IllegalStateException("Handshake refused with HTTP " + handshake.getResponse().statusCode()
                            + " " + handshake.getResponse().body(), handshake);
                }
                throw new IllegalStateException(cause);
            }).get(10, TimeUnit.SECONDS);
        }

        private HttpRequest.Builder request(String path) {
            return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Origin", "http://127.0.0.1:" + port);
        }
    }
}
