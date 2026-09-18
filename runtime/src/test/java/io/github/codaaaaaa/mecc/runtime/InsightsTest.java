package io.github.codaaaaaa.mecc.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.codaaaaaa.mecc.core.command.ChatReply;
import io.github.codaaaaaa.mecc.core.users.PlayerProfile;
import java.net.CookieManager;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Milestone 5 exit criterion: several players watch overlapping resources without duplicate sampling, and see
 * correct history after a restart. Runs the real runtime, HTTP server, and database against a fake platform.
 */
class InsightsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String IRON = "item:minecraft:iron_ingot";
    private static final String GOLD = "item:minecraft:gold_ingot";

    @TempDir
    Path dir;

    private final PlayerProfile steve = new PlayerProfile(UUID.randomUUID(), "Steve");
    private final PlayerProfile alex = new PlayerProfile(UUID.randomUUID(), "Alex");
    private final PlayerProfile eve = new PlayerProfile(UUID.randomUUID(), "Eve");
    private FakePlatform platform;
    private MeccRuntime runtime;
    private int port;
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
                [resources]
                snapshot_max_age_seconds = 1
                [analytics]
                sample_interval_seconds = 300
                max_watchlist_entries_per_user = 2
                """.formatted(port));
        platform = new FakePlatform(dir, Map.of());
        platform.addGrid("grid-steve", steve, 10);
        platform.addResource("item", "minecraft", "iron_ingot", 2_400, true);
        platform.addResource("item", "minecraft", "gold_ingot", 64, false);
        platform.addResource("item", "minecraft", "dirt", 5, false);
        startRuntime();
    }

    private void startRuntime() throws Exception {
        runtime = new MeccRuntime(platform);
        runtime.start();
        runtime.startupFuture().get(30, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        runtime.stop();
        platform.close();
    }

    @Test
    void overlappingWatchlistsShareOneSamplePerNetworkAndSurviveARestart() throws Exception {
        Browser steveBrowser = pair(steve);
        Browser alexBrowser = pair(alex);
        Browser eveBrowser = pair(eve);
        networkId = json(steveBrowser.post("/api/v1/networks",
                Map.of("candidateKey", "minecraft:overworld@10,64,0", "displayName", "Base")))
                .path("network").path("id").asText();
        json(steveBrowser.post("/api/v1/networks/" + networkId + "/members", Map.of("player", "alex", "role", "VIEWER")));

        // Viewers keep a personal watchlist; watching twice is harmless.
        JsonNode steveIron = json(steveBrowser.post("/api/v1/watchlist", watch(IRON)));
        assertEquals(201, steveBrowser.post("/api/v1/watchlist", watch(IRON)).statusCode());
        json(alexBrowser.post("/api/v1/watchlist", watch(IRON)));
        json(alexBrowser.post("/api/v1/watchlist", watch(GOLD)));
        assertEquals("CONFLICT", errorCode(alexBrowser.post("/api/v1/watchlist", watch("item:minecraft:dirt"))),
                "per-player limit");
        assertEquals("RESOURCE_NOT_FOUND", errorCode(steveBrowser.post("/api/v1/watchlist", watch("item:minecraft:stone"))));
        assertEquals("NETWORK_NOT_FOUND", errorCode(eveBrowser.post("/api/v1/watchlist", watch(IRON))));
        assertEquals("NETWORK_NOT_FOUND", errorCode(eveBrowser.get(series("1h", null))));
        assertEquals("NOT_FOUND", errorCode(alexBrowser.delete("/api/v1/watchlist/" + steveIron.path("id").asText())),
                "someone else's entry");

        JsonNode steveList = json(steveBrowser.get("/api/v1/watchlist?networkId=" + networkId));
        assertEquals(1, steveList.path("entries").size());
        assertEquals(2_400, steveList.path("entries").get(0).path("amount").asLong());
        assertTrue(steveList.path("entries").get(0).path("craftable").asBoolean());

        // Three subscriptions, two distinct resources, one network: one storage read, two series.
        Thread.sleep(1_100); // Let the cached snapshot expire.
        int captures = platform.storageCaptures.get();
        assertEquals(2, runtime.insightsSampler().sampleOnce().get(10, TimeUnit.SECONDS));
        assertEquals(captures + 1, platform.storageCaptures.get());

        platform.storage.removeIf(entry -> entry.descriptor().id().toString().equals(GOLD));
        Thread.sleep(1_100);
        assertEquals(2, runtime.insightsSampler().sampleOnce().get(10, TimeUnit.SECONDS));

        // Restart: history comes back from the database.
        runtime.stop();
        startRuntime();
        JsonNode set = json(alexBrowser.get(series("1h", null)));
        assertEquals("RAW", set.path("resolution").asText());
        assertEquals(300, set.path("stepSeconds").asLong(), "the raw step is the sampling interval");
        // Both samples may share one 15 s bucket, so compare the range they cover.
        assertEquals(List.of(2_400L, 2_400L), minMax(set, IRON));
        assertEquals(List.of(0L, 64L), minMax(set, GOLD), "gone from storage while online reads as 0");
        assertEquals(1, json(alexBrowser.get(series("max", GOLD))).path("series").size(), "filtered to one resource");

        // An unloaded network records nothing (a gap), not zeros.
        platform.grids.clear();
        Thread.sleep(3_000); // Discovery notices the network is gone.
        assertEquals(0, runtime.insightsSampler().sampleOnce().get(10, TimeUnit.SECONDS));
        assertTrue(json(steveBrowser.get("/api/v1/watchlist?networkId=" + networkId))
                .path("entries").get(0).path("amount").isNull(), "amount unknown while offline");

        assertEquals(204, steveBrowser.delete("/api/v1/watchlist/" + steveIron.path("id").asText()).statusCode());
        assertEquals(0, json(steveBrowser.get(series("1h", null))).path("series").size());
    }

    private Map<String, String> watch(String resourceId) {
        return Map.of("networkId", networkId, "resourceId", resourceId);
    }

    private String series(String range, String resource) {
        return "/api/v1/insights/series?networkId=" + networkId + "&range=" + range
                + (resource == null ? "" : "&resource=" + resource);
    }

    /** Smallest minimum and largest maximum over a series' points. */
    private static List<Long> minMax(JsonNode set, String resourceId) {
        for (JsonNode series : set.path("series")) {
            if (series.path("resourceId").asText().equals(resourceId)) {
                long min = Long.MAX_VALUE;
                long max = Long.MIN_VALUE;
                for (JsonNode point : series.path("points")) {
                    min = Math.min(min, point.get(2).asLong());
                    max = Math.max(max, point.get(3).asLong());
                }
                return List.of(min, max);
            }
        }
        throw new AssertionError("no series for " + resourceId);
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
            return client.send(request(path).header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        private HttpRequest.Builder request(String path) {
            return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header("Origin", "http://127.0.0.1:" + port);
        }
    }
}
