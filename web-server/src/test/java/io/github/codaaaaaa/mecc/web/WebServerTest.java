package io.github.codaaaaaa.mecc.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.codaaaaaa.mecc.core.error.ErrorCode;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import io.github.codaaaaaa.mecc.web.api.ApiRoutes;
import io.github.codaaaaaa.mecc.web.api.ApiSecurity;
import io.github.codaaaaaa.mecc.web.api.SessionResolver;
import io.github.codaaaaaa.mecc.web.staticfiles.StaticAssets;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebServerTest {

    private static final String INDEX_HTML = "<!doctype html><title>ME Control Center</title><div id=root></div>";

    record Sample(String name, Instant at) {
    }

    private final HttpClient client = HttpClient.newHttpClient();
    private WebServer server;

    @BeforeEach
    void setUp() {
        ApiRoutes routes = ApiRoutes.builder()
                .publicGet("/api/v1/sample", request -> CompletableFuture.completedFuture(
                        new Sample("ok", Instant.parse("2026-09-17T12:00:00Z"))))
                .publicGet("/api/v1/busy", request -> CompletableFuture.failedFuture(
                        new MeccException(ErrorCode.GATEWAY_BUSY, "busy")))
                .publicGet("/api/v1/crash", request -> {
                    throw new IllegalStateException("secret internal detail");
                })
                .build();
        StaticAssets assets = StaticAssets.of(Map.of(
                "index.html", INDEX_HTML.getBytes(StandardCharsets.UTF_8),
                "assets/index-abc123.js", "console.log('mecc')".getBytes(StandardCharsets.UTF_8)));
        server = new WebServer("127.0.0.1", 0, 8, routes, assets, ApiSecurity.defaults(), SessionResolver.none());
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop();
    }

    @Test
    void servesJsonFromApiEndpoint() throws Exception {
        HttpResponse<String> response = get("/api/v1/sample");
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("content-type").orElseThrow().startsWith("application/json"));
        assertEquals("{\"name\":\"ok\",\"at\":\"2026-09-17T12:00:00Z\"}", response.body());
    }

    @Test
    void mapsDomainErrorsToStructuredResponses() throws Exception {
        HttpResponse<String> busy = get("/api/v1/busy");
        assertEquals(503, busy.statusCode());
        assertEquals("{\"error\":{\"code\":\"GATEWAY_BUSY\",\"message\":\"busy\",\"details\":{}}}", busy.body());

        HttpResponse<String> missing = get("/api/v1/nope");
        assertEquals(404, missing.statusCode());
        assertTrue(missing.body().contains("\"NOT_FOUND\""));
    }

    @Test
    void neverLeaksInternalExceptionDetails() throws Exception {
        HttpResponse<String> response = get("/api/v1/crash");
        assertEquals(500, response.statusCode());
        assertTrue(response.body().contains("\"INTERNAL_ERROR\""));
        assertFalse(response.body().contains("secret"));
    }

    @Test
    void rejectsWrongMethodOnApi() throws Exception {
        HttpResponse<String> response = client.send(
                request("/api/v1/sample").POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, response.statusCode());
        assertEquals("GET", response.headers().firstValue("allow").orElseThrow());
    }

    @Test
    void servesIndexAndFallsBackForClientRoutes() throws Exception {
        for (String path : new String[] {"/", "/overview", "/network/abc"}) {
            HttpResponse<String> response = get(path);
            assertEquals(200, response.statusCode(), path);
            assertEquals(INDEX_HTML, response.body(), path);
            assertEquals("no-cache", response.headers().firstValue("cache-control").orElseThrow(), path);
        }
        assertEquals(404, get("/missing.js").statusCode());
    }

    @Test
    void cachesHashedAssetsAndSupportsEtags() throws Exception {
        HttpResponse<String> response = get("/assets/index-abc123.js");
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("content-type").orElseThrow().startsWith("text/javascript"));
        assertTrue(response.headers().firstValue("cache-control").orElseThrow().contains("immutable"));

        String etag = response.headers().firstValue("etag").orElseThrow();
        HttpResponse<String> revalidated = client.send(
                request("/assets/index-abc123.js").header("If-None-Match", etag).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(304, revalidated.statusCode());
    }

    @Test
    void addsSecurityHeadersAndHidesServerVersion() throws Exception {
        HttpResponse<String> response = get("/");
        assertEquals("nosniff", response.headers().firstValue("x-content-type-options").orElseThrow());
        assertEquals("DENY", response.headers().firstValue("x-frame-options").orElseThrow());
        assertTrue(response.headers().firstValue("content-security-policy").orElseThrow().contains("default-src 'self'"));
        assertTrue(response.headers().firstValue("server").isEmpty());
    }

    @Test
    void reportsBindFailureAsMeccException() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))) {
            WebServer conflicting = new WebServer("127.0.0.1", occupied.getLocalPort(), 8,
                    ApiRoutes.builder().build(), StaticAssets.empty(), ApiSecurity.defaults(), SessionResolver.none());
            assertThrows(MeccException.class, conflicting::start);
            assertFalse(conflicting.isRunning());
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(request(path).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.boundPort() + path));
    }
}
