package io.github.codaaaaaa.mecc.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Milestone 1 exit criterion: two different players on a public server can pair different browsers and
 * only see networks they are authorized to access. Runs the real runtime, HTTP server, and SQLite
 * database against a fake Minecraft platform.
 */
class MultiplayerAccessTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private final PlayerProfile steve = new PlayerProfile(UUID.randomUUID(), "Steve");
    private final PlayerProfile alex = new PlayerProfile(UUID.randomUUID(), "Alex");
    private final PlayerProfile admin = new PlayerProfile(UUID.randomUUID(), "Admin");
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
                [networks]
                discovery_interval_seconds = 2
                """.formatted(port));
        platform = new FakePlatform(dir, Map.of());
        runtime = new MeccRuntime(platform);
        runtime.start();
        runtime.startupFuture().get(20, TimeUnit.SECONDS);
        assertEquals(port, runtime.webPort());
    }

    @AfterEach
    void tearDown() {
        runtime.stop();
        platform.close();
    }

    @Test
    void playersOnlySeeNetworksTheyMayAccess() throws Exception {
        String steveAnchor = platform.addGrid("grid-steve", steve, 10).key();
        String alexAnchor = platform.addGrid("grid-alex", alex, 500).key();

        Browser steveBrowser = new Browser();
        Browser alexBrowser = new Browser();

        // Unpaired browsers cannot see anything.
        assertEquals("UNAUTHENTICATED", errorCode(steveBrowser.get("/api/v1/networks")));

        // Pairing: in-game key -> browser device cookie.
        String steveKey = pairingKey(steve);
        HttpResponse<String> paired = steveBrowser.post("/api/v1/auth/pair", Map.of("key", steveKey, "deviceName", "Steve's laptop"));
        assertEquals(201, paired.statusCode(), paired.body());
        String cookie = paired.headers().firstValue("set-cookie").orElseThrow();
        assertTrue(cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict"), cookie);
        assertEquals("Steve", json(steveBrowser.get("/api/v1/me")).path("user").path("playerName").asText());

        // A pairing key works once, even from another browser.
        assertEquals("PAIRING_KEY_INVALID", errorCode(alexBrowser.post("/api/v1/auth/pair", Map.of("key", steveKey))));
        assertEquals(201, alexBrowser.post("/api/v1/auth/pair", Map.of("key", pairingKey(alex))).statusCode());
        assertEquals("Alex", json(alexBrowser.get("/api/v1/me")).path("user").path("playerName").asText());

        // Each player may only claim the network whose Wireless Access Point they own.
        JsonNode steveCandidates = json(steveBrowser.get("/api/v1/networks/candidates")).path("candidates");
        assertEquals(1, steveCandidates.size());
        assertEquals(steveAnchor, steveCandidates.get(0).path("key").asText());
        assertEquals("NETWORK_CANDIDATE_NOT_FOUND",
                errorCode(alexBrowser.post("/api/v1/networks", Map.of("candidateKey", steveAnchor, "displayName", "Stolen"))));

        HttpResponse<String> claimed = steveBrowser.post("/api/v1/networks", Map.of("candidateKey", steveAnchor, "displayName", "Steve Base"));
        assertEquals(201, claimed.statusCode(), claimed.body());
        JsonNode steveNetwork = json(claimed);
        String steveNetworkId = steveNetwork.path("network").path("id").asText();
        assertEquals("OWNER", steveNetwork.path("network").path("role").asText());
        assertEquals("ONLINE", steveNetwork.path("network").path("state").asText());
        assertEquals(1234, steveNetwork.path("status").path("storedResourceTypes").asInt());
        assertTrue(steveNetwork.path("status").path("patternProviders").isNull(), "unsupported values are null, not zero");

        String alexNetworkId = json(alexBrowser.post("/api/v1/networks", Map.of("candidateKey", alexAnchor, "displayName", "Alex Base")))
                .path("network").path("id").asText();

        // Isolation.
        assertEquals(List.of("Steve Base"), networkNames(steveBrowser));
        assertEquals(List.of("Alex Base"), networkNames(alexBrowser));
        assertEquals("NETWORK_NOT_FOUND", errorCode(alexBrowser.get("/api/v1/networks/" + steveNetworkId)));
        assertEquals("NETWORK_NOT_FOUND", errorCode(alexBrowser.get("/api/v1/networks/" + UUID.randomUUID())));
        assertEquals("NETWORK_NOT_FOUND", errorCode(alexBrowser.get("/api/v1/networks/not-a-uuid")));
        assertEquals("NETWORK_NOT_FOUND", errorCode(alexBrowser.get("/api/v1/networks/" + steveNetworkId + "/members")));
        assertEquals("NETWORK_NOT_FOUND", errorCode(alexBrowser.patch("/api/v1/networks/" + steveNetworkId, Map.of("name", "Mine"))));

        // Sharing grants exactly the granted role.
        HttpResponse<String> shared = steveBrowser.post("/api/v1/networks/" + steveNetworkId + "/members",
                Map.of("player", "alex", "role", "VIEWER"));
        assertEquals(200, shared.statusCode(), shared.body());
        assertEquals(List.of("Alex Base", "Steve Base"), networkNames(alexBrowser));
        assertEquals("VIEWER", json(alexBrowser.get("/api/v1/networks/" + steveNetworkId)).path("network").path("role").asText());
        assertEquals("PERMISSION_DENIED", errorCode(alexBrowser.patch("/api/v1/networks/" + steveNetworkId, Map.of("name", "Mine"))));
        assertEquals("PERMISSION_DENIED", errorCode(alexBrowser.post("/api/v1/networks/" + steveNetworkId + "/members",
                Map.of("player", "Alex", "role", "OWNER"))));

        // A member may leave; afterwards the network is invisible again.
        assertEquals(204, alexBrowser.delete("/api/v1/networks/" + steveNetworkId + "/members/" + alex.uuid()).statusCode());
        assertEquals(List.of("Alex Base"), networkNames(alexBrowser));
        assertEquals(alexNetworkId, json(alexBrowser.get("/api/v1/networks")).path("networks").get(0).path("id").asText());
    }

    @Test
    void revokedDevicesLoseAccessImmediately() throws Exception {
        Browser laptop = new Browser();
        Browser phone = new Browser();
        assertEquals(201, laptop.post("/api/v1/auth/pair", Map.of("key", pairingKey(steve))).statusCode());
        assertEquals(201, phone.post("/api/v1/auth/pair", Map.of("key", pairingKey(steve))).statusCode());

        JsonNode devices = json(laptop.get("/api/v1/devices")).path("devices");
        assertEquals(2, devices.size());

        // Revoke from the laptop: "revoke all except current".
        assertEquals(1, json(laptop.post("/api/v1/devices/revoke-others", Map.of())).path("revoked").asInt());
        assertEquals("UNAUTHENTICATED", errorCode(phone.get("/api/v1/me")));
        assertEquals(200, laptop.get("/api/v1/me").statusCode());

        // In-game /mecc revoke: another player cannot revoke Steve's device, Steve can.
        String laptopId = json(laptop.get("/api/v1/me")).path("device").path("id").asText();
        ChatReply denied = runtime.commands().revoke(alex, 0, laptopId, "en_us").toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertFalse(denied.success());
        assertEquals(200, laptop.get("/api/v1/me").statusCode());

        ChatReply listed = runtime.commands().listDevices(steve, 0, null, "zh_cn").toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(text(listed).contains(laptopId), text(listed));

        ChatReply revoked = runtime.commands().revoke(steve, 0, laptopId, "en_us").toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(revoked.success(), text(revoked));
        assertEquals("UNAUTHENTICATED", errorCode(laptop.get("/api/v1/me")));
    }

    @Test
    void serverAdminOverrideSeesEverythingAndIsAudited() throws Exception {
        String steveAnchor = platform.addGrid("grid-steve", steve, 10).key();
        platform.opLevels.put(admin.uuid(), 4);
        Browser steveBrowser = new Browser();
        Browser adminBrowser = new Browser();
        steveBrowser.post("/api/v1/auth/pair", Map.of("key", pairingKey(steve)));
        adminBrowser.post("/api/v1/auth/pair", Map.of("key", pairingKey(admin)));
        String networkId = json(steveBrowser.post("/api/v1/networks", Map.of("candidateKey", steveAnchor, "displayName", "Base")))
                .path("network").path("id").asText();

        assertTrue(json(adminBrowser.get("/api/v1/me")).path("serverAdmin").asBoolean());
        JsonNode seen = json(adminBrowser.get("/api/v1/networks")).path("networks");
        assertEquals(1, seen.size());
        assertTrue(seen.get(0).path("adminOverride").asBoolean());
        assertEquals(200, adminBrowser.patch("/api/v1/networks/" + networkId, Map.of("name", "Renamed by admin")).statusCode());
        assertEquals(List.of("Renamed by admin"), networkNames(steveBrowser));
    }

    @Test
    void auditLogIsVisibleToOwnersAndAdminsOnly() throws Exception {
        platform.addResource("item", "minecraft", "iron_ingot", 5, false);
        String steveAnchor = platform.addGrid("grid-steve", steve, 10).key();
        platform.opLevels.put(admin.uuid(), 4);
        Browser steveBrowser = new Browser();
        Browser alexBrowser = new Browser();
        Browser adminBrowser = new Browser();
        steveBrowser.post("/api/v1/auth/pair", Map.of("key", pairingKey(steve)));
        alexBrowser.post("/api/v1/auth/pair", Map.of("key", pairingKey(alex)));
        adminBrowser.post("/api/v1/auth/pair", Map.of("key", pairingKey(admin)));
        String networkId = json(steveBrowser.post("/api/v1/networks", Map.of("candidateKey", steveAnchor, "displayName", "Base")))
                .path("network").path("id").asText();
        String audit = "/api/v1/networks/" + networkId + "/audit";

        assertEquals("NETWORK_NOT_FOUND", errorCode(alexBrowser.get(audit)));
        steveBrowser.post("/api/v1/networks/" + networkId + "/members", Map.of("player", "Alex", "role", "MANAGER"));
        assertEquals("PERMISSION_DENIED", errorCode(alexBrowser.get(audit)), "Owner only");

        JsonNode log = json(steveBrowser.get(audit)).path("entries");
        assertEquals(List.of("NETWORK_SHARE", "NETWORK_CLAIM"), List.of(log.get(0).path("action").asText(),
                log.get(1).path("action").asText()));
        assertEquals("Steve", log.get(0).path("actor").path("playerName").asText());
        assertEquals("Alex", log.get(0).path("targetPlayer").path("playerName").asText());
        assertEquals("Base", log.get(0).path("networkName").asText());

        // Server administration: admins only.
        assertEquals("PERMISSION_DENIED", errorCode(steveBrowser.get("/api/v1/admin")));
        assertEquals("PERMISSION_DENIED", errorCode(steveBrowser.get("/api/v1/admin/audit")));
        assertEquals("PERMISSION_DENIED", errorCode(steveBrowser.post("/api/v1/admin/backups", Map.of())));

        JsonNode overview = json(adminBrowser.get("/api/v1/admin"));
        assertEquals(port, overview.path("config").path("web").path("port").asInt());
        assertEquals(1200, overview.path("config").path("security").path("rateLimitRequestsPerMinute").asInt());
        assertTrue(overview.path("config").path("security").path("trustedProxyRanges").isMissingNode());
        assertTrue(overview.path("database").path("schemaVersion").asInt() > 0);
        assertEquals(0, overview.path("database").path("backups").size());

        HttpResponse<String> backup = adminBrowser.post("/api/v1/admin/backups", Map.of());
        assertEquals(201, backup.statusCode(), backup.body());
        String backupName = json(backup).path("name").asText();
        assertTrue(Files.isRegularFile(platform.dataDirectory().resolve("backups").resolve(backupName)));
        assertEquals(backupName, json(adminBrowser.get("/api/v1/admin")).path("database").path("backups").get(0)
                .path("name").asText());

        JsonNode first = json(adminBrowser.get("/api/v1/admin/audit?limit=1"));
        assertEquals("DATABASE_BACKUP", first.path("entries").get(0).path("action").asText());
        JsonNode older = json(adminBrowser.get("/api/v1/admin/audit?limit=10&before=" + first.path("nextBefore").asLong()));
        assertTrue(older.path("nextBefore").isNull());
        assertTrue(older.path("entries").size() >= 3, "pairings, claim, share: " + older);
        assertEquals(200, adminBrowser.get(audit).statusCode(), "admin override reads any network's log");

        // A removed member's watchlist on the network goes with the membership.
        assertEquals(201, alexBrowser.post("/api/v1/watchlist",
                Map.of("networkId", networkId, "resourceId", "item:minecraft:iron_ingot")).statusCode());
        assertEquals(1, runtime.insightsSampler().sampleOnce().get(5, TimeUnit.SECONDS));
        assertEquals(204, steveBrowser.delete("/api/v1/networks/" + networkId + "/members/" + alex.uuid()).statusCode());
        assertEquals(0, runtime.insightsSampler().sampleOnce().get(5, TimeUnit.SECONDS));
    }

    @Test
    void conflictingIdentityIsReportedAndNeverMerged() throws Exception {
        platform.addGrid("grid-steve", steve, 10);
        platform.addGrid("grid-alex", alex, 500);
        Browser steveBrowser = new Browser();
        Browser alexBrowser = new Browser();
        steveBrowser.post("/api/v1/auth/pair", Map.of("key", pairingKey(steve)));
        alexBrowser.post("/api/v1/auth/pair", Map.of("key", pairingKey(alex)));
        String steveId = json(steveBrowser.post("/api/v1/networks", Map.of("candidateKey", "minecraft:overworld@10,64,0", "displayName", "S")))
                .path("network").path("id").asText();
        json(alexBrowser.post("/api/v1/networks", Map.of("candidateKey", "minecraft:overworld@500,64,0", "displayName", "A")));

        // Someone connects both bases with a cable: one grid now holds both anchors.
        var steveGrid = platform.grids.get(0);
        var alexGrid = platform.grids.get(1);
        platform.grids.clear();
        platform.grids.add(new io.github.codaaaaaa.mecc.core.networks.DiscoverySnapshot.DiscoveredGrid("grid-merged",
                List.of(steveGrid.anchors().get(0), alexGrid.anchors().get(0)), FakePlatform.POWERED));
        // Candidates forces a fresh discovery pass.
        steveBrowser.get("/api/v1/networks/candidates");

        JsonNode detail = json(steveBrowser.get("/api/v1/networks/" + steveId));
        assertEquals("CONFLICT", detail.path("network").path("state").asText());
        assertEquals("MERGED", detail.path("network").path("stateReason").asText());
        assertTrue(detail.path("status").isNull(), "no live data while identity is ambiguous");
        assertEquals(List.of("S"), networkNames(steveBrowser), "records stay separate");
        assertEquals(List.of("A"), networkNames(alexBrowser));
    }

    // --- helpers ------------------------------------------------------------------------------------

    private String pairingKey(PlayerProfile player) {
        ChatReply reply = runtime.commands().pair(player, "en_us");
        assertTrue(reply.success(), text(reply));
        return reply.lines().stream()
                .flatMap(line -> line.spans().stream())
                .filter(span -> span.style() == ChatReply.Style.SECRET)
                .findFirst()
                .orElseThrow()
                .text();
    }

    private static String text(ChatReply reply) {
        StringBuilder text = new StringBuilder();
        reply.lines().forEach(line -> {
            line.spans().forEach(span -> text.append(span.text()));
            text.append('\n');
        });
        return text.toString();
    }

    private static List<String> networkNames(Browser browser) throws Exception {
        JsonNode networks = json(browser.get("/api/v1/networks")).path("networks");
        List<String> names = new java.util.ArrayList<>();
        networks.forEach(network -> names.add(network.path("displayName").asText()));
        return names;
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

    /** One browser profile: its own cookie jar. */
    private final class Browser {
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

        HttpResponse<String> get(String path) throws Exception {
            return send(request(path).GET());
        }

        HttpResponse<String> post(String path, Object body) throws Exception {
            return send(request(path).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
        }

        HttpResponse<String> patch(String path, Object body) throws Exception {
            return send(request(path).header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
        }

        HttpResponse<String> delete(String path) throws Exception {
            return send(request(path).DELETE());
        }

        private HttpRequest.Builder request(String path) {
            return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header("Origin", "http://127.0.0.1:" + port);
        }

        private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
