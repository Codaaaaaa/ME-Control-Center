package io.github.codaaaaaa.mecc.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.codaaaaaa.mecc.core.assets.AssetPack;
import io.github.codaaaaaa.mecc.core.command.ChatReply;
import io.github.codaaaaaa.mecc.core.resources.ResourceText;
import io.github.codaaaaaa.mecc.core.users.PlayerProfile;
import java.net.CookieManager;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Milestone 2 exit criterion: a large modded ME storage can be browsed with icons and without the server
 * thread doing the heavy work. Runs the real runtime, HTTP server, database, and icon renderer against a
 * fake Minecraft platform.
 */
class ResourceTerminalTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private final PlayerProfile steve = new PlayerProfile(UUID.randomUUID(), "Steve");
    private final PlayerProfile alex = new PlayerProfile(UUID.randomUUID(), "Alex");
    private FakePlatform platform;
    private MeccRuntime runtime;
    private Browser browser;
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
                """.formatted(port));
        platform = new FakePlatform(dir, Map.of());
        platform.modNames.put("mekanism", "Mekanism");
        platform.modNames.put("minecraft", "Minecraft");
        platform.resourceTags.put("item:minecraft:iron_ingot", List.of("forge:ingots/iron"));
        platform.assetPacks.add(assetPack());

        platform.addGrid("grid-steve", steve, 10);
        platform.addResource("item", "minecraft", "iron_ingot", 2_400, true);
        platform.addResource("item", "minecraft", "gold_ingot", 64, false);
        platform.addResource("item", "mekanism", "ingot_osmium", 128, true);
        platform.addResource("fluid", "minecraft", "water", 16_000, false);

        runtime = new MeccRuntime(platform);
        runtime.start();
        runtime.startupFuture().get(30, TimeUnit.SECONDS);
        browser = new Browser();
        browser.post("/api/v1/auth/pair", Map.of("key", pairingKey(steve)));
        networkId = json(browser.post("/api/v1/networks",
                Map.of("candidateKey", "minecraft:overworld@10,64,0", "displayName", "Base")))
                .path("network").path("id").asText();
    }

    @AfterEach
    void tearDown() {
        runtime.stop();
        platform.close();
    }

    @Test
    void listsResourcesWithNamesUnitsAndCraftableState() throws Exception {
        JsonNode page = json(browser.get(resources("")));
        assertEquals(4, page.path("total").asInt());
        List<String> names = names(page);
        assertEquals(List.of("Gold Ingot", "Ingot Osmium", "Iron Ingot", "Water"), names, "sorted by name");

        JsonNode iron = entry(page, "item:minecraft:iron_ingot");
        assertEquals(2_400, iron.path("amount").asLong());
        assertTrue(iron.path("craftable").asBoolean());
        assertTrue(iron.path("crafting").isNull());
        assertEquals("Minecraft", iron.path("modName").asText());
        assertEquals("item/minecraft/iron_ingot", iron.path("iconKey").asText());
        assertTrue(iron.path("unit").isNull(), "counted resources have no unit");

        JsonNode water = entry(page, "fluid:minecraft:water");
        assertEquals("B", water.path("unit").path("symbol").asText());
        assertEquals(1000, water.path("unit").path("amountPerUnit").asInt());
        assertFalse(page.path("assetVersion").asText().isEmpty());
    }

    @Test
    void searchesAndSortsServerSide() throws Exception {
        assertEquals(List.of("Ingot Osmium"), names(json(browser.get(resources("&q=@mekanism")))));
        assertEquals(List.of("Iron Ingot"), names(json(browser.get(resources("&q=%23forge:ingots")))), "tag search");
        assertEquals(List.of("Water"), names(json(browser.get(resources("&type=FLUID")))));
        assertEquals(List.of("Gold Ingot", "Ingot Osmium"), names(json(browser.get(resources("&q=amount:%3C1000")))));
        assertEquals(List.of("Ingot Osmium", "Iron Ingot"), names(json(browser.get(resources("&q=craftable:true")))));
        assertEquals(List.of("Water", "Iron Ingot", "Ingot Osmium", "Gold Ingot"),
                names(json(browser.get(resources("&sort=AMOUNT&desc=true")))));
        assertEquals(List.of("Ingot Osmium", "Gold Ingot", "Iron Ingot", "Water"),
                names(json(browser.get(resources("&sort=MOD")))), "mod, then name");
        assertEquals(List.of("Ingot Osmium", "Iron Ingot", "Gold Ingot", "Water"),
                names(json(browser.get(resources("&sort=CRAFTABLE")))), "craftable first");
    }

    @Test
    void pagesConsistentlyFromOneSnapshot() throws Exception {
        JsonNode first = json(browser.get(resources("&limit=2")));
        String snapshot = first.path("snapshotId").asText();
        assertEquals(2, first.path("entries").size());

        platform.storage.clear();
        Thread.sleep(1100); // The snapshot is now older than resources.snapshot_max_age_seconds.

        JsonNode second = json(browser.get(resources("&limit=2&offset=2&snapshot=" + snapshot)));
        assertEquals(snapshot, second.path("snapshotId").asText(), "pinned snapshot keeps paging stable");
        assertEquals(List.of("Iron Ingot", "Water"), names(second));

        JsonNode fresh = json(browser.get(resources("&limit=2")));
        assertNotEquals(snapshot, fresh.path("snapshotId").asText());
        assertEquals(0, fresh.path("total").asInt(), "the emptied network is reported honestly");
    }

    @Test
    void showsResourceDetailWithTagsAndRejectsUnknownResources() throws Exception {
        JsonNode detail = json(browser.get("/api/v1/networks/" + networkId + "/resources/detail?id="
                + encode("item:minecraft:iron_ingot")));
        assertEquals("minecraft:iron_ingot", detail.path("registryId").asText());
        assertEquals("Iron Ingot", detail.path("resource").path("name").asText());
        assertEquals(List.of("forge:ingots/iron"), JSON.convertValue(detail.path("tags"), List.class));
        assertTrue(detail.path("variant").isNull());

        assertEquals("RESOURCE_NOT_FOUND", errorCode(browser.get("/api/v1/networks/" + networkId
                + "/resources/detail?id=" + encode("item:minecraft:missing"))));
    }

    @Test
    void enforcesPermissionsAndNetworkState() throws Exception {
        Browser outsider = new Browser();
        outsider.post("/api/v1/auth/pair", Map.of("key", pairingKey(alex)));
        assertEquals("NETWORK_NOT_FOUND", errorCode(outsider.get(resources(""))));

        // A Viewer may browse the terminal.
        browser.post("/api/v1/networks/" + networkId + "/members", Map.of("player", "Alex", "role", "VIEWER"));
        assertEquals(4, json(outsider.get(resources(""))).path("total").asInt());

        // The network stops being loaded: the terminal says so instead of showing an empty grid.
        platform.grids.clear();
        browser.get("/api/v1/networks/candidates");
        assertEquals("NETWORK_OFFLINE", errorCode(browser.get(resources(""))));
    }

    @Test
    void servesRenderedIconsWithLongLivedCaching() throws Exception {
        String assetVersion = json(browser.get(resources(""))).path("assetVersion").asText();
        HttpResponse<byte[]> icon = browser.getBytes("/api/v1/icons?key=item/demo/gem&v=" + assetVersion);
        assertEquals(200, icon.statusCode());
        assertEquals("image/png", icon.headers().firstValue("content-type").orElseThrow());
        assertTrue(icon.headers().firstValue("cache-control").orElseThrow().contains("max-age"));
        BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(icon.body()));
        assertEquals(0xFFFF0000, image.getRGB(32, 32), "the demo texture is red");

        String etag = icon.headers().firstValue("etag").orElseThrow();
        assertEquals(304, browser.getBytesIfNoneMatch("/api/v1/icons?key=item/demo/gem", etag).statusCode());
        assertEquals("ICON_NOT_FOUND", errorCode(browser.get("/api/v1/icons?key=item/demo/unknown")));
    }

    @Test
    void handlesTensOfThousandsOfResourcesWithoutHeavyServerThreadWork() throws Exception {
        platform.storage.clear();
        for (int i = 0; i < 50_000; i++) {
            platform.addResource("item", "bigpack", "material_" + i, i + 1, i % 7 == 0);
        }
        Thread.sleep(1100);

        long start = System.nanoTime();
        JsonNode page = json(browser.get(resources("&limit=120")));
        Duration firstPage = Duration.ofNanos(System.nanoTime() - start);
        assertEquals(50_000, page.path("total").asInt());
        assertEquals(120, page.path("entries").size());

        start = System.nanoTime();
        JsonNode searched = json(browser.get(resources("&q=material_49999&snapshot=" + page.path("snapshotId").asText())));
        Duration search = Duration.ofNanos(System.nanoTime() - start);
        assertEquals(1, searched.path("total").asInt());

        // The server thread only copies the inventory; names, sorting, and searching happen off it.
        assertTrue(platform.lastCaptureDuration.toMillis() < 250,
                "server-thread capture took " + platform.lastCaptureDuration.toMillis() + " ms");
        assertTrue(firstPage.toMillis() < 15_000, "first page took " + firstPage.toMillis() + " ms");
        assertTrue(search.toMillis() < 3_000, "search took " + search.toMillis() + " ms");
    }

    // --- helpers ------------------------------------------------------------------------------------

    @Test
    void composesNamesFromArgumentsAndTurnsFormattingCodesIntoSpans() throws Exception {
        // Added before the first snapshot of this test, so the shared fixture stays as the other tests read it.
        platform.addResource("item", "demo", "sodium_dust", 7, false, ResourceText.translatable(
                "item.demo.dust", List.of(ResourceText.translatable("material.demo.sodium", List.of()))));
        platform.addResource("item", "demo", "grinder", 1, false,
                ResourceText.literal("\u00a79\u00a7l传奇研磨机\u00a7r"));

        JsonNode english = json(browser.get(resources("")));
        JsonNode dust = entry(english, "item:demo:sodium_dust");
        assertEquals("Sodium Dust", dust.path("name").asText(), "the material argument must survive translation");
        assertTrue(dust.path("nameSpans").isNull(), "an unstyled name needs no spans");

        JsonNode chinese = json(browser.get("/api/v1/networks/" + networkId + "/resources?locale=zh_cn"));
        assertEquals("钠粉", entry(chinese, "item:demo:sodium_dust").path("name").asText());

        JsonNode grinder = entry(english, "item:demo:grinder");
        assertEquals("传奇研磨机", grinder.path("name").asText(), "no formatting codes reach the terminal");
        JsonNode spans = grinder.path("nameSpans");
        assertEquals(1, spans.size());
        assertEquals("传奇研磨机", spans.get(0).path("text").asText());
        assertEquals("#5555FF", spans.get(0).path("color").asText());
        assertTrue(spans.get(0).path("bold").asBoolean());
    }

    private String resources(String extra) {
        return "/api/v1/networks/" + networkId + "/resources?locale=en_us" + extra;
    }

    private static List<String> names(JsonNode page) {
        List<String> names = new ArrayList<>();
        page.path("entries").forEach(entry -> names.add(entry.path("name").asText()));
        return names;
    }

    private static JsonNode entry(JsonNode page, String id) {
        for (JsonNode entry : page.path("entries")) {
            if (entry.path("id").asText().equals(id)) {
                return entry;
            }
        }
        throw new AssertionError("No entry " + id + " in " + page);
    }

    private AssetPack assetPack() throws Exception {
        Path root = dir.resolve("assets-pack");
        Path lang = root.resolve("assets/demo/lang/en_us.json");
        Files.createDirectories(lang.getParent());
        Files.writeString(lang, "{\"item.demo.dust\":\"%s Dust\",\"material.demo.sodium\":\"Sodium\"}",
                StandardCharsets.UTF_8);
        Files.writeString(root.resolve("assets/demo/lang/zh_cn.json"),
                "{\"item.demo.dust\":\"%s粉\",\"material.demo.sodium\":\"钠\"}", StandardCharsets.UTF_8);
        Path model = root.resolve("assets/demo/models/item/gem.json");
        Files.createDirectories(model.getParent());
        Files.writeString(model, "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"demo:item/gem\"}}");
        Path texture = root.resolve("assets/demo/textures/item/gem.png");
        Files.createDirectories(texture.getParent());
        BufferedImage red = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                red.setRGB(x, y, 0xFFFF0000);
            }
        }
        ImageIO.write(red, "png", texture.toFile());
        return new AssetPack("demo", root, "1");
    }

    private String pairingKey(PlayerProfile player) {
        ChatReply reply = runtime.commands().pair(player, "en_us");
        return reply.lines().stream().flatMap(line -> line.spans().stream())
                .filter(span -> span.style() == ChatReply.Style.SECRET)
                .findFirst().orElseThrow().text();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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

        HttpResponse<byte[]> getBytes(String path) throws Exception {
            return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        }

        HttpResponse<byte[]> getBytesIfNoneMatch(String path, String etag) throws Exception {
            return client.send(request(path).header("If-None-Match", etag).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
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
