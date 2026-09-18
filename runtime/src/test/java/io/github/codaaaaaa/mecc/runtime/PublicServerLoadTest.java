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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Milestone 6 public-server load test: many players, each from their own address behind a trusted reverse
 * proxy, browse, search, and chart one large shared network at the same time (about 280 requests per second
 * in total). Requirements: nothing fails or gets rate limited at normal use, responses stay fast, and storage is still read from the server thread at
 * most once per snapshot interval however many browsers are open (spec sections 21.1, 33, 48).
 */
class PublicServerLoadTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int PLAYERS = 40;
    /** Long enough to span several snapshot intervals. */
    private static final long DURATION_NANOS = TimeUnit.SECONDS.toNanos(12);
    private static final int RESOURCES = 30_000;
    private static final int SNAPSHOT_MAX_AGE_SECONDS = 5;

    @TempDir
    Path dir;

    private FakePlatform platform;
    private MeccRuntime runtime;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        port = freePort();
        Files.writeString(dir.resolve("mecc.toml"), """
                [web]
                host = "127.0.0.1"
                port = %d
                max_threads = 64
                [security]
                trusted_proxies = ["127.0.0.1"]
                [networks]
                discovery_interval_seconds = 2
                [resources]
                snapshot_max_age_seconds = %d
                [analytics]
                sample_interval_seconds = 5
                """.formatted(port, SNAPSHOT_MAX_AGE_SECONDS));
        platform = new FakePlatform(dir, Map.of());
        for (int i = 0; i < RESOURCES; i++) {
            platform.addResource("item", "bigpack", "material_" + i, i + 1, i % 7 == 0);
        }
        runtime = new MeccRuntime(platform);
        runtime.start();
        runtime.startupFuture().get(20, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        runtime.stop();
        platform.close();
    }

    @Test
    void manyPlayersShareOneLargeNetwork() throws Exception {
        PlayerProfile owner = new PlayerProfile(UUID.randomUUID(), "Owner");
        String anchor = platform.addGrid("grid-shared", owner, 10).key();
        Player ownerBrowser = new Player(owner, "198.51.100.1");
        String networkId = json(ownerBrowser.post("/api/v1/networks", Map.of("candidateKey", anchor, "displayName", "Shared")))
                .path("network").path("id").asText();

        List<Player> players = new ArrayList<>();
        for (int i = 0; i < PLAYERS; i++) {
            Player player = new Player(new PlayerProfile(UUID.randomUUID(), "Player" + i), "203.0.113." + (i + 1));
            json(ownerBrowser.post("/api/v1/networks/" + networkId + "/members",
                    Map.of("player", player.profile.name(), "role", "VIEWER")));
            // Overlapping watchlists: 40 players, 120 entries, 10 distinct series.
            for (int j = 0; j < 3; j++) {
                json(player.post("/api/v1/watchlist", Map.of("networkId", networkId,
                        "resourceId", "item:bigpack:material_" + ((i + j) % 10))));
            }
            players.add(player);
        }

        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        Map<String, Integer> failures = new ConcurrentHashMap<>();
        int capturesBefore = platform.storageCaptures.get();
        long start = System.nanoTime();
        long deadline = start + DURATION_NANOS;
        ExecutorService pool = Executors.newFixedThreadPool(PLAYERS);
        try {
            List<Future<?>> sessions = new ArrayList<>();
            for (Player player : players) {
                sessions.add(pool.submit(() -> {
                    String base = "/api/v1/networks/" + networkId;
                    while (System.nanoTime() < deadline) {
                        ThreadLocalRandom random = ThreadLocalRandom.current();
                        for (String path : List.of(
                                base + "/resources?limit=120&sort=amount&desc=true&q=material_" + random.nextInt(RESOURCES),
                                base + "/resources?limit=120&offset=" + 120 * random.nextInt(RESOURCES / 120),
                                base,
                                "/api/v1/watchlist?networkId=" + networkId,
                                "/api/v1/insights/series?range=1h&networkId=" + networkId)) {
                            // A busy but human player: several requests per second, far below the rate limit.
                            Thread.sleep(random.nextLong(50, 250));
                            long sent = System.nanoTime();
                            HttpResponse<String> response = player.get(path);
                            latencies.add(System.nanoTime() - sent);
                            if (response.statusCode() != 200) {
                                failures.merge(response.statusCode() + " " + path.replaceAll("\\?.*", ""), 1, Integer::sum);
                            }
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> session : sessions) {
                session.get(5, TimeUnit.MINUTES);
            }
        } finally {
            pool.shutdownNow();
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        int captures = platform.storageCaptures.get() - capturesBefore;

        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        long p50 = sorted.get(sorted.size() / 2) / 1_000_000;
        long p95 = sorted.get(sorted.size() * 95 / 100) / 1_000_000;
        System.out.printf("Load test: %d players, %d requests in %.1f s (%.0f req/s), p50 %d ms, p95 %d ms, "
                        + "%d storage captures, last capture %d ms on the server thread%n", PLAYERS, sorted.size(), seconds,
                sorted.size() / seconds, p50, p95, captures, platform.lastCaptureDuration.toMillis());

        assertEquals(Map.of(), failures, "no request may fail or be rate limited at normal use");
        assertTrue(p95 < 3_000, "p95 latency " + p95 + " ms");
        assertTrue(captures >= 1, "storage was read while browsing");
        assertTrue(captures <= seconds / SNAPSHOT_MAX_AGE_SECONDS + 2,
                captures + " storage captures in " + seconds + " s: snapshots must be shared between browsers");
        assertTrue(platform.lastCaptureDuration.toMillis() < 250,
                "server-thread capture took " + platform.lastCaptureDuration.toMillis() + " ms");
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        assertTrue(response.statusCode() < 300, response.statusCode() + " " + response.body());
        return JSON.readTree(response.body());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    /** One player's browser, reaching the server through the trusted proxy from its own address. */
    private final class Player {
        private final PlayerProfile profile;
        private final String address;
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

        Player(PlayerProfile profile, String address) throws Exception {
            this.profile = profile;
            this.address = address;
            ChatReply reply = runtime.commands().pair(profile, "en_us");
            String key = reply.lines().stream()
                    .flatMap(line -> line.spans().stream())
                    .filter(span -> span.style() == ChatReply.Style.SECRET)
                    .findFirst()
                    .orElseThrow()
                    .text();
            json(post("/api/v1/auth/pair", Map.of("key", key)));
        }

        HttpResponse<String> get(String path) throws Exception {
            return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> post(String path, Object body) throws Exception {
            return client.send(request(path).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        private HttpRequest.Builder request(String path) {
            return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header("Origin", "http://127.0.0.1:" + port)
                    .header("X-Forwarded-For", address);
        }
    }
}
