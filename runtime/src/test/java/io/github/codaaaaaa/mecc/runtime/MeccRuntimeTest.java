package io.github.codaaaaaa.mecc.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MeccRuntimeTest {

    @TempDir
    Path configDir;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void servesStatusAndWebUiEndToEnd() throws Exception {
        int port = freePort();
        Files.writeString(configDir.resolve("mecc.toml"), "[web]\nhost = \"127.0.0.1\"\nport = " + port + "\n");
        Map<String, String> bundle = Map.of(
                "mecc-web/asset-index.txt", "index.html\n",
                "mecc-web/index.html", "<!doctype html><div id=root></div>");

        try (FakePlatform platform = new FakePlatform(configDir, bundle)) {
            MeccRuntime runtime = new MeccRuntime(platform);
            runtime.start();
            runtime.startupFuture().get(10, TimeUnit.SECONDS);
            try {
                assertEquals(port, runtime.webPort());

                HttpResponse<String> status = get(port, "/api/v1/status");
                assertEquals(200, status.statusCode());
                assertTrue(status.body().contains("\"state\":\"RUNNING\""), status.body());
                assertTrue(status.body().contains("\"playersOnline\":3"), status.body());
                assertTrue(status.body().contains("\"tested\":true"), status.body());

                HttpResponse<String> index = get(port, "/");
                assertEquals(200, index.statusCode());
                assertTrue(index.body().contains("id=root"));

                platform.running = false;
                HttpResponse<String> degraded = get(port, "/api/v1/status");
                assertEquals(200, degraded.statusCode());
                assertTrue(degraded.body().contains("\"state\":\"UNAVAILABLE\""), degraded.body());
                assertTrue(degraded.body().contains("\"server\":null"), degraded.body());
            } finally {
                runtime.stop();
            }
            assertEquals(-1, runtime.webPort());
        }
    }

    @Test
    void invalidConfigDoesNotThrowAndStartsNoServer() throws Exception {
        Files.writeString(configDir.resolve("mecc.toml"), "[web]\nport = 99999\n");
        try (FakePlatform platform = new FakePlatform(configDir, Map.of())) {
            MeccRuntime runtime = new MeccRuntime(platform);
            runtime.start();
            runtime.startupFuture().get(10, TimeUnit.SECONDS);
            assertEquals(-1, runtime.webPort());
            runtime.stop();
        }
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }
}
