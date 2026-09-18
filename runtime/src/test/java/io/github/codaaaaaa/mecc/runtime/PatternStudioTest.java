package io.github.codaaaaaa.mecc.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.codaaaaaa.mecc.core.command.ChatReply;
import io.github.codaaaaaa.mecc.core.patterns.PatternType;
import io.github.codaaaaaa.mecc.core.resources.ResourceDescriptor;
import io.github.codaaaaaa.mecc.core.users.PlayerProfile;
import io.github.codaaaaaa.mecc.platform.RecipePlatform;
import io.github.codaaaaaa.mecc.platform.RecipePlatform.PatternRecipe;
import io.github.codaaaaaa.mecc.runtime.FakePlatform.FakeProvider;
import java.net.CookieManager;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Milestone 4 exit criterion: a Manager can create a new pattern in ME Control Center and deploy it into a loaded
 * Pattern Provider without opening Minecraft's Pattern Encoding Terminal. Runs the real runtime, HTTP server, and
 * database against a fake Minecraft platform.
 */
class PatternStudioTest {
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
    private FakeProvider assembler;

    @BeforeEach
    void setUp() throws Exception {
        port = freePort();
        Files.writeString(dir.resolve("mecc.toml"), """
                [web]
                host = "127.0.0.1"
                port = %d
                [networks]
                discovery_interval_seconds = 2
                [patterns]
                max_pattern_inputs = 4
                max_drafts_per_user = 3
                """.formatted(port));
        platform = new FakePlatform(dir, Map.of());
        platform.addGrid("grid-steve", steve, 10);
        for (String path : List.of("oak_planks", "stick", "stone", "stone_bricks", "stone_slab", "iron_ingot", "diamond")) {
            platform.recipes.register("item", "minecraft", path);
        }
        platform.recipes.register("fluid", "minecraft", "water");
        ResourceDescriptor plank = FakePlatform.descriptor("item", "minecraft", "oak_planks");
        List<List<ResourceDescriptor>> stickGrid = new ArrayList<>(Collections.nCopies(9, List.of()));
        stickGrid.set(0, List.of(plank));
        stickGrid.set(3, List.of(plank));
        platform.recipes.list.add(new PatternRecipe("minecraft:stick", PatternType.CRAFTING, stickGrid,
                FakePlatform.descriptor("item", "minecraft", "stick"), 4));
        ResourceDescriptor stone = FakePlatform.descriptor("item", "minecraft", "stone");
        platform.recipes.list.add(new PatternRecipe("minecraft:stone_bricks_from_stonecutting", PatternType.STONECUTTING,
                List.of(List.of(stone)), FakePlatform.descriptor("item", "minecraft", "stone_bricks"), 1));
        platform.recipes.list.add(new PatternRecipe("minecraft:stone_slab_from_stonecutting", PatternType.STONECUTTING,
                List.of(List.of(stone)), FakePlatform.descriptor("item", "minecraft", "stone_slab"), 2));
        platform.patterns.blankPatterns.set(2);
        assembler = platform.patterns.addProvider("p-assembler", "Molecular Assembler", 2);

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
    void managerDraftsFillsFromRecipeValidatesAndDeploys() throws Exception {
        Browser manager = member(alex, "MANAGER");

        // Fill from recipe: the server's own recipes, found by output.
        JsonNode recipes = json(manager.get("/api/v1/patterns/recipes?type=CRAFTING&output=item:minecraft:stick&locale=en_us"));
        assertEquals(1, recipes.path("recipes").size());
        JsonNode recipe = recipes.path("recipes").get(0);
        assertEquals("minecraft:stick", recipe.path("id").asText());
        assertEquals(9, recipe.path("slots").size());
        assertTrue(recipe.path("slots").get(1).isNull(), "empty grid cells stay empty");
        assertEquals("item:minecraft:oak_planks", recipe.path("slots").get(0).path("options").get(0).path("id").asText());

        Map<String, Object> sticks = craftingDefinition(Map.of(0, "item:minecraft:oak_planks", 3, "item:minecraft:oak_planks"));
        JsonNode draft = json(manager.post("/api/v1/patterns/drafts",
                Map.of("name", "Sticks", "networkId", networkId, "definition", sticks)));
        String draftId = draft.path("id").asText();
        assertEquals("CRAFTING", draft.path("definition").path("type").asText());
        assertTrue(draft.path("definition").path("inputs").get(1).isNull());
        assertEquals("item:minecraft:oak_planks", draft.path("definition").path("inputs").get(3).path("resource").path("id").asText());
        assertEquals(1, json(manager.get("/api/v1/patterns/drafts")).path("drafts").size());
        assertEquals("DRAFT_NOT_FOUND", errorCode(owner.get("/api/v1/patterns/drafts/" + draftId)), "drafts are personal");

        JsonNode validation = json(manager.post(patterns("/validate"), Map.of("definition", sticks)));
        assertTrue(validation.path("valid").asBoolean(), validation.toString());
        assertEquals("item:minecraft:stick", validation.path("outputs").get(0).path("resource").path("id").asText());
        assertEquals(4, validation.path("outputs").get(0).path("amount").asLong());
        assertEquals(2, validation.path("blankPatterns").asLong());

        JsonNode providers = json(manager.get("/api/v1/networks/" + networkId + "/providers"));
        assertTrue(providers.path("canDeploy").asBoolean());
        JsonNode provider = providers.path("providers").get(0);
        assertEquals("p-assembler", provider.path("id").asText());
        assertEquals(0, provider.path("usedSlots").asInt());

        JsonNode deployed = json(manager.post(patterns("/deploy"),
                Map.of("definition", sticks, "draftId", draftId, "providerId", "p-assembler")));
        assertEquals("DEPLOY", deployed.path("action").asText());
        assertEquals("Molecular Assembler", deployed.path("providerName").asText());
        assertEquals(0, deployed.path("slot").asInt());
        assertEquals(1, platform.patterns.blankPatterns.get(), "exactly one Blank Pattern consumed");
        assertEquals(1, assembler.used());

        JsonNode after = json(manager.get("/api/v1/networks/" + networkId + "/providers")).path("providers").get(0);
        assertEquals(1, after.path("usedSlots").asInt(), "the provider list shows the new pattern at once");
        assertEquals("item:minecraft:stick", after.path("patterns").get(0).path("outputs").get(0).path("resource").path("id").asText());

        JsonNode history = json(manager.get(patterns("/deployments"))).path("deployments");
        assertEquals(1, history.size());
        assertEquals("Alex", history.get(0).path("actor").path("playerName").asText());
        assertEquals("item:minecraft:stick", history.get(0).path("output").path("id").asText());
        assertTrue(history.get(0).path("errorCode").isNull());
    }

    @Test
    void processingAndStonecuttingEncodeIntoStorage() throws Exception {
        Map<String, Object> mud = definition("PROCESSING",
                List.of(stack("item:minecraft:stone", 1), stack("fluid:minecraft:water", 250)),
                List.of(stack("item:minecraft:diamond", 3)), null);
        JsonNode encoded = json(owner.post(patterns("/encode"), Map.of("definition", mud)));
        assertEquals("ENCODE", encoded.path("action").asText());
        assertTrue(encoded.path("providerId").isNull());
        assertEquals(3, encoded.path("outputs").get(0).path("amount").asLong());
        assertEquals(1, platform.patterns.inStorage.size());

        JsonNode choices = json(owner.get("/api/v1/patterns/recipes?type=STONECUTTING&input=item:minecraft:stone"));
        assertEquals(2, choices.path("recipes").size(), "one recipe per stonecutter output");

        Map<String, Object> slab = definition("STONECUTTING", List.of(stack("item:minecraft:stone", 1)), List.of(), null);
        JsonNode missingRecipe = json(owner.post(patterns("/validate"), Map.of("definition", slab)));
        assertFalse(missingRecipe.path("valid").asBoolean());
        assertEquals("RECIPE_REQUIRED", missingRecipe.path("issues").get(0).path("code").asText());

        slab = definition("STONECUTTING", List.of(stack("item:minecraft:stone", 1)), List.of(),
                "minecraft:stone_slab_from_stonecutting");
        JsonNode valid = json(owner.post(patterns("/validate"), Map.of("definition", slab)));
        assertTrue(valid.path("valid").asBoolean(), valid.toString());
        assertEquals("item:minecraft:stone_slab", valid.path("outputs").get(0).path("resource").path("id").asText());
        json(owner.post(patterns("/encode"), Map.of("definition", slab)));
        assertEquals(0, platform.patterns.blankPatterns.get());

        HttpResponse<String> none = owner.post(patterns("/encode"), Map.of("definition", slab));
        assertEquals("NO_BLANK_PATTERN", errorCode(none));
        assertEquals(2, platform.patterns.inStorage.size(), "nothing encoded without a Blank Pattern");
        assertEquals("NO_BLANK_PATTERN", json(owner.get(patterns("/deployments"))).path("deployments").get(0)
                .path("errorCode").asText(), "failures are kept as history");
    }

    @Test
    void failuresNeverConsumeBlankPatterns() throws Exception {
        Map<String, Object> sticks = craftingDefinition(Map.of(0, "item:minecraft:oak_planks", 3, "item:minecraft:oak_planks"));

        assertEquals("PATTERN_INVALID", errorCode(owner.post(patterns("/encode"),
                Map.of("definition", craftingDefinition(Map.of(0, "item:minecraft:diamond"))))));
        JsonNode unknown = json(owner.post(patterns("/validate"),
                Map.of("definition", craftingDefinition(Map.of(0, "item:othermod:gadget")))));
        assertEquals("UNKNOWN_RESOURCE", unknown.path("issues").get(0).path("code").asText());
        assertEquals("inputs[0]", unknown.path("issues").get(0).path("field").asText());
        JsonNode tooMany = json(owner.post(patterns("/validate"), Map.of("definition", definition("PROCESSING",
                List.of(stack("item:minecraft:stone", 1), stack("item:minecraft:stone", 1), stack("item:minecraft:stone", 1),
                        stack("item:minecraft:stone", 1), stack("item:minecraft:stone", 1)),
                List.of(stack("item:minecraft:diamond", 1)), null))));
        assertEquals("TOO_MANY_INPUTS", tooMany.path("issues").get(0).path("code").asText(), "configured limit applies");

        assertEquals("PROVIDER_NOT_FOUND", errorCode(owner.post(patterns("/deploy"),
                Map.of("definition", sticks, "providerId", "p-missing"))));
        assembler.online = false;
        assertEquals("PROVIDER_OFFLINE", errorCode(owner.post(patterns("/deploy"),
                Map.of("definition", sticks, "providerId", "p-assembler"))));
        assembler.online = true;
        assembler.refuseNext = true;
        HttpResponse<String> refused = owner.post(patterns("/deploy"), Map.of("definition", sticks, "providerId", "p-assembler"));
        assertEquals("DEPLOY_FAILED", errorCode(refused));
        assertTrue(JSON.readTree(refused.body()).path("error").path("details").path("blankReturned").asBoolean());
        assertEquals(2, platform.patterns.blankPatterns.get(), "no failure consumed a Blank Pattern");

        json(owner.post(patterns("/deploy"), Map.of("definition", sticks, "providerId", "p-assembler")));
        json(owner.post(patterns("/deploy"), Map.of("definition", sticks, "providerId", "p-assembler")));
        platform.patterns.blankPatterns.set(5);
        assertEquals("PROVIDER_FULL", errorCode(owner.post(patterns("/deploy"),
                Map.of("definition", sticks, "providerId", "p-assembler"))));
        assertEquals(5, platform.patterns.blankPatterns.get());
    }

    @Test
    void rolesGuardPatternStudio() throws Exception {
        Browser viewer = member(eve, "VIEWER");
        Browser operator = member(alex, "OPERATOR");
        Map<String, Object> sticks = craftingDefinition(Map.of(0, "item:minecraft:oak_planks", 3, "item:minecraft:oak_planks"));

        JsonNode providers = json(viewer.get("/api/v1/networks/" + networkId + "/providers"));
        assertFalse(providers.path("canDeploy").asBoolean(), "viewers may look at providers but not deploy");
        assertEquals("PERMISSION_DENIED", errorCode(viewer.post(patterns("/validate"), Map.of("definition", sticks))));
        assertEquals("PERMISSION_DENIED", errorCode(operator.post(patterns("/encode"), Map.of("definition", sticks))));
        assertEquals("PERMISSION_DENIED", errorCode(operator.post(patterns("/deploy"),
                Map.of("definition", sticks, "providerId", "p-assembler"))));
        assertEquals(2, platform.patterns.blankPatterns.get());

        Browser stranger = pair(new PlayerProfile(UUID.randomUUID(), "Stranger"));
        assertEquals("NETWORK_NOT_FOUND", errorCode(stranger.get("/api/v1/networks/" + networkId + "/providers")));
        assertEquals("NETWORK_NOT_FOUND", errorCode(stranger.post("/api/v1/patterns/drafts",
                Map.of("name", "Mine", "networkId", networkId, "definition", sticks))), "drafts cannot point at hidden networks");
    }

    @Test
    void draftsAreValidatedAndLimited() throws Exception {
        Map<String, Object> empty = definition("PROCESSING", List.of(), List.of(), null);
        JsonNode incomplete = json(owner.post("/api/v1/patterns/drafts", Map.of("name", " Work in progress ", "definition", empty)));
        assertEquals("Work in progress", incomplete.path("name").asText(), "incomplete drafts are fine");
        String id = incomplete.path("id").asText();

        assertEquals("VALIDATION_FAILED", errorCode(owner.post("/api/v1/patterns/drafts",
                Map.of("name", "", "definition", empty))));
        assertEquals("VALIDATION_FAILED", errorCode(owner.post("/api/v1/patterns/drafts",
                Map.of("name", "Bad", "definition", definition("CRAFTING", List.of(stack("item:minecraft:stone", 1)), List.of(), null)))),
                "a crafting grid always has nine cells");
        assertEquals("VALIDATION_FAILED", errorCode(owner.post("/api/v1/patterns/drafts",
                Map.of("name", "Bad", "definition", definition("PROCESSING", List.of(stack("not an id", 1)), List.of(), null)))));

        JsonNode renamed = json(owner.patch("/api/v1/patterns/drafts/" + id, Map.of("name", "Renamed", "definition",
                definition("PROCESSING", List.of(stack("item:minecraft:stone", 2)), List.of(stack("item:minecraft:diamond", 1)), null))));
        assertEquals("Renamed", renamed.path("name").asText());
        assertEquals(2, renamed.path("definition").path("inputs").get(0).path("amount").asLong());

        json(owner.post("/api/v1/patterns/drafts", Map.of("name", "Two", "definition", empty)));
        json(owner.post("/api/v1/patterns/drafts", Map.of("name", "Three", "definition", empty)));
        assertEquals("CONFLICT", errorCode(owner.post("/api/v1/patterns/drafts", Map.of("name", "Four", "definition", empty))));

        assertEquals(204, owner.delete("/api/v1/patterns/drafts/" + id).statusCode());
        assertEquals("DRAFT_NOT_FOUND", errorCode(owner.get("/api/v1/patterns/drafts/" + id)));
        assertEquals(2, json(owner.get("/api/v1/patterns/drafts")).path("drafts").size());
    }

    @Test
    void patternBuffersShowTheirMultiblockAndCanBeRenamed() throws Exception {
        FakeProvider buffer = platform.patterns.addProvider("p-buffer", "ME Pattern Buffer", 36);
        buffer.machine = FakePlatform.descriptor("item", "gtceu", "large_assembler");
        Browser manager = member(alex, "MANAGER");
        Browser operator = member(eve, "OPERATOR");

        JsonNode list = json(manager.get("/api/v1/networks/" + networkId + "/providers"));
        assertTrue(list.path("canConfigure").asBoolean());
        JsonNode found = null;
        for (JsonNode provider : list.path("providers")) {
            if (provider.path("id").asText().equals("p-buffer")) {
                found = provider;
            }
        }
        assertEquals("item:gtceu:large_assembler", found.path("machine").path("id").asText(), "the multiblock is known");
        assertEquals("item:gtceu:me_pattern_buffer", found.path("kind").path("id").asText());
        assertTrue(found.path("priority").isNull(), "buffers have no AE2 provider settings");

        assertEquals("PERMISSION_DENIED", errorCode(operator.patch("/api/v1/networks/" + networkId + "/providers/p-buffer",
                Map.of("name", "Mine"))));
        assertEquals(204, manager.patch("/api/v1/networks/" + networkId + "/providers/p-buffer",
                Map.of("name", "  Titanium line  ")).statusCode());
        assertEquals("Titanium line", buffer.name);
        assertTrue(json(manager.get("/api/v1/networks/" + networkId + "/providers")).toString().contains("Titanium line"),
                "the list is refreshed at once");
        assertEquals("PROVIDER_NOT_FOUND", errorCode(manager.patch("/api/v1/networks/" + networkId + "/providers/p-none",
                Map.of("name", "x"))));
        buffer.renamable = false;
        assertEquals("PROVIDER_NOT_RENAMABLE", errorCode(manager.patch("/api/v1/networks/" + networkId + "/providers/p-buffer",
                Map.of("name", "x"))));
    }

    @Test
    void machineRecipesFillProcessingPatterns() throws Exception {
        platform.recipes.list.add(new PatternRecipe("gtceu:assembler/hv_hatch", PatternType.PROCESSING,
                List.of(List.of(FakePlatform.descriptor("item", "minecraft", "iron_ingot")),
                        List.of(FakePlatform.descriptor("fluid", "minecraft", "water"))),
                List.of(4L, 144L), FakePlatform.descriptor("item", "minecraft", "diamond"), 1,
                List.of(new RecipePlatform.Byproduct(FakePlatform.descriptor("item", "minecraft", "stick"), 2)),
                new RecipePlatform.Category("gtceu:assembler", null), true));
        platform.recipes.list.add(new PatternRecipe("minecraft:diamond_from_smelting", PatternType.PROCESSING,
                List.of(List.of(FakePlatform.descriptor("item", "minecraft", "stone"))), null,
                FakePlatform.descriptor("item", "minecraft", "diamond"), 1, List.of(),
                new RecipePlatform.Category("minecraft:smelting", null), false));

        JsonNode byOutput = json(owner.get("/api/v1/patterns/recipes?type=PROCESSING&output=item:minecraft:diamond"));
        assertEquals(2, byOutput.path("recipes").size());
        JsonNode assembler = byOutput.path("recipes").get(0);
        assertEquals("gtceu:assembler/hv_hatch", assembler.path("id").asText(), "complete recipes come first");
        assertEquals("gtceu:assembler", assembler.path("category").path("id").asText());
        assertEquals(144, assembler.path("slots").get(1).path("amount").asLong());
        assertEquals("fluid:minecraft:water", assembler.path("slots").get(1).path("options").get(0).path("id").asText());
        assertEquals("item:minecraft:stick", assembler.path("byproducts").get(0).path("resource").path("id").asText());
        assertFalse(byOutput.path("recipes").get(1).path("complete").asBoolean());

        JsonNode byByproduct = json(owner.get("/api/v1/patterns/recipes?type=PROCESSING&output=item:minecraft:stick"));
        assertEquals("gtceu:assembler/hv_hatch", byByproduct.path("recipes").get(0).path("id").asText(),
                "machine recipes are found by any guaranteed output");
    }

    @Test
    void catalogListsRegisteredResourcesNotInStorage() throws Exception {
        JsonNode page = json(owner.get("/api/v1/patterns/catalog?q=stone&limit=10&locale=en_us"));
        List<String> ids = new ArrayList<>();
        page.path("entries").forEach(entry -> ids.add(entry.path("id").asText()));
        assertTrue(ids.contains("item:minecraft:stone_bricks"), ids.toString());
        assertTrue(page.path("total").asInt() >= 3);
        assertEquals(1, platform.recipes.captures.get(), "recipes are read once, not per request");
        json(owner.get("/api/v1/patterns/catalog?q=water&type=fluid"));
        assertEquals(1, platform.recipes.captures.get());

        platform.tagsVersion++;
        json(owner.get("/api/v1/patterns/catalog?q=water"));
        assertEquals(2, platform.recipes.captures.get(), "a data pack reload reads recipes again");
    }

    // --- helpers ------------------------------------------------------------------------------------

    private static Map<String, Object> craftingDefinition(Map<Integer, String> cells) {
        List<Object> inputs = new ArrayList<>(Collections.nCopies(9, null));
        cells.forEach((cell, id) -> inputs.set(cell, stack(id, 1)));
        return definition("CRAFTING", inputs, List.of(), null);
    }

    private static Map<String, Object> definition(String type, List<?> inputs, List<?> outputs, String recipeId) {
        Map<String, Object> definition = new HashMap<>();
        definition.put("type", type);
        definition.put("inputs", inputs);
        definition.put("outputs", outputs);
        definition.put("recipeId", recipeId);
        return definition;
    }

    private static Map<String, Object> stack(String resource, long amount) {
        return Map.of("resource", resource, "amount", amount);
    }

    private String patterns(String path) {
        return "/api/v1/networks/" + networkId + "/patterns" + path;
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

    private String pairingKey(PlayerProfile player) {
        ChatReply reply = runtime.commands().pair(player, "en_us");
        return reply.lines().stream().flatMap(line -> line.spans().stream())
                .filter(span -> span.style() == ChatReply.Style.SECRET)
                .findFirst().orElseThrow().text();
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

        HttpResponse<String> post(String path, Object body) throws Exception {
            return send("POST", path, body);
        }

        HttpResponse<String> patch(String path, Object body) throws Exception {
            return send("PATCH", path, body);
        }

        HttpResponse<String> delete(String path) throws Exception {
            return client.send(request(path).DELETE().build(), HttpResponse.BodyHandlers.ofString());
        }

        private HttpResponse<String> send(String method, String path, Object body) throws Exception {
            return client.send(request(path).header("Content-Type", "application/json")
                            .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        private HttpRequest.Builder request(String path) {
            return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Origin", "http://127.0.0.1:" + port);
        }
    }
}
