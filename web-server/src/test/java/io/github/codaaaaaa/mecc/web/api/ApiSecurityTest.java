package io.github.codaaaaaa.mecc.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.codaaaaaa.mecc.core.auth.ClientInfo;
import io.github.codaaaaaa.mecc.core.auth.Device;
import io.github.codaaaaaa.mecc.core.auth.Session;
import io.github.codaaaaaa.mecc.core.net.IpRange;
import io.github.codaaaaaa.mecc.core.users.WebUser;
import io.github.codaaaaaa.mecc.web.WebServer;
import io.github.codaaaaaa.mecc.web.staticfiles.StaticAssets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** HTTP-level security behavior of the API pipeline (spec section 36). */
class ApiSecurityTest {
    private static final String TOKEN = "t".repeat(43);

    record Echo(String text) {
    }

    private final HttpClient client = HttpClient.newHttpClient();
    private final AtomicReference<ClientInfo> lastClient = new AtomicReference<>();
    private WebServer server;

    @BeforeEach
    void setUp() {
        Instant now = Instant.parse("2026-09-17T12:00:00Z");
        WebUser user = new WebUser(UUID.randomUUID(), "Steve", now, now);
        Session session = new Session(user, new Device("dev", user.playerUuid(), "d", null, now, now, null, null), false, false);
        SessionResolver sessions = (token, clientInfo) -> CompletableFuture.completedFuture(
                TOKEN.equals(token) ? Optional.of(session) : Optional.empty());

        ApiRoutes routes = ApiRoutes.builder()
                .get("/api/v1/private", request -> CompletableFuture.completedFuture(
                        new Echo(request.session().user().playerName())))
                .post("/api/v1/private", request -> CompletableFuture.completedFuture(request.body(Echo.class)))
                .publicPost("/api/v1/echo", request -> {
                    lastClient.set(request.client());
                    return CompletableFuture.completedFuture(request.body(Echo.class));
                })
                .publicGet("/api/v1/items/{id}", request -> CompletableFuture.completedFuture(new Echo(request.pathParameter("id"))))
                .publicGet("/api/v1/items/special", request -> CompletableFuture.completedFuture(new Echo("literal wins")))
                .build();
        ApiSecurity security = new ApiSecurity(List.of(IpRange.parse("127.0.0.1").orElseThrow()), false,
                Set.of("https://mecc.example.org"), 1024, 60, 10);
        server = new WebServer("127.0.0.1", 0, 8, routes, StaticAssets.empty(), security, sessions);
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop();
    }

    @Test
    void authenticatedRoutesRequireAValidToken() throws Exception {
        assertEquals(401, send(get("/api/v1/private")).statusCode());

        HttpResponse<String> stale = send(get("/api/v1/private").header("Cookie", "mecc_device=" + "x".repeat(43)));
        assertEquals(401, stale.statusCode());
        String clearing = stale.headers().firstValue("set-cookie").orElseThrow();
        assertTrue(clearing.contains("Max-Age=0"), clearing);

        HttpResponse<String> cookie = send(get("/api/v1/private").header("Cookie", "mecc_device=" + TOKEN));
        assertEquals(200, cookie.statusCode());
        assertEquals("{\"text\":\"Steve\"}", cookie.body());

        assertEquals(200, send(get("/api/v1/private").header("Authorization", "Bearer " + TOKEN)).statusCode());
    }

    @Test
    void rejectsForeignOriginsOnStateChangingRequests() throws Exception {
        assertEquals(403, send(post("/api/v1/echo", "{\"text\":\"x\"}").header("Origin", "https://evil.example")).statusCode());
        assertEquals(403, send(post("/api/v1/echo", "{\"text\":\"x\"}").header("Sec-Fetch-Site", "cross-site")).statusCode());
        assertEquals(200, send(post("/api/v1/echo", "{\"text\":\"x\"}").header("Origin", "https://mecc.example.org")).statusCode());
        assertEquals(200, send(post("/api/v1/echo", "{\"text\":\"x\"}")
                .header("Origin", "http://127.0.0.1:" + server.boundPort())).statusCode(), "same host");
    }

    @Test
    void requiresJsonAndLimitsBodySize() throws Exception {
        HttpResponse<String> form = send(request("/api/v1/echo")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("text=x")));
        assertEquals(415, form.statusCode());

        HttpResponse<String> large = send(post("/api/v1/echo", "{\"text\":\"" + "a".repeat(2000) + "\"}"));
        assertEquals(413, large.statusCode());
        assertTrue(large.body().contains("PAYLOAD_TOO_LARGE"));

        HttpResponse<String> malformed = send(post("/api/v1/echo", "{not json"));
        assertEquals(400, malformed.statusCode());
    }

    @Test
    void trustsForwardedForOnlyFromConfiguredProxies() throws Exception {
        // The test client connects from 127.0.0.1, which is configured as a trusted proxy.
        send(post("/api/v1/echo", "{\"text\":\"x\"}").header("X-Forwarded-For", "203.0.113.9, 127.0.0.1"));
        assertEquals("203.0.113.9", lastClient.get().address());
    }

    @Test
    void limitsRequestsPerAddressAndChangesPerPlayer() throws Exception {
        for (int i = 0; i < 60; i++) {
            assertEquals(200, send(get("/api/v1/items/a").header("X-Forwarded-For", "203.0.113.1")).statusCode());
        }
        HttpResponse<String> limited = send(get("/api/v1/items/a").header("X-Forwarded-For", "203.0.113.1"));
        assertEquals(429, limited.statusCode());
        assertTrue(limited.body().contains("RATE_LIMITED"), limited.body());
        assertEquals(200, send(get("/api/v1/items/a").header("X-Forwarded-For", "203.0.113.2")).statusCode(),
                "other addresses are unaffected");

        // Changes count per player, whichever address they come from.
        for (int i = 0; i < 10; i++) {
            assertEquals(200, send(post("/api/v1/private", "{\"text\":\"x\"}").header("Authorization", "Bearer " + TOKEN)
                    .header("X-Forwarded-For", "198.51.100." + i)).statusCode());
        }
        assertEquals(429, send(post("/api/v1/private", "{\"text\":\"x\"}").header("Authorization", "Bearer " + TOKEN)
                .header("X-Forwarded-For", "198.51.100.99")).statusCode());
        assertEquals(200, send(get("/api/v1/private").header("Authorization", "Bearer " + TOKEN)
                .header("X-Forwarded-For", "198.51.100.99")).statusCode(), "reading is still allowed");
    }

    @Test
    void matchesPathTemplatesPreferringLiterals() throws Exception {
        assertEquals("{\"text\":\"abc\"}", send(get("/api/v1/items/abc")).body());
        assertEquals("{\"text\":\"literal wins\"}", send(get("/api/v1/items/special")).body());
        assertEquals(404, send(get("/api/v1/items/abc/more")).statusCode());
    }

    private HttpRequest.Builder get(String path) {
        return request(path).GET();
    }

    private HttpRequest.Builder post(String path, String json) {
        return request(path).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.boundPort() + path));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
