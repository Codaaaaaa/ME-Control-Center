package io.github.codaaaaaa.mecc.assets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.codaaaaaa.mecc.core.assets.AssetPack;
import io.github.codaaaaaa.mecc.core.assets.IconService.IconImage;
import io.github.codaaaaaa.mecc.core.resources.NameSpan;
import io.github.codaaaaaa.mecc.core.resources.ResourceDescriptor;
import io.github.codaaaaaa.mecc.core.resources.ResourceId;
import io.github.codaaaaaa.mecc.core.resources.ResourceText;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssetsTest {
    private static final int RED = 0xFFFF0000;
    private static final int GREEN = 0xFF00FF00;
    private static final int BLUE = 0xFF0000FF;

    @TempDir
    Path dir;

    private Path base;
    private Path override;

    @BeforeEach
    void setUp() throws Exception {
        base = dir.resolve("base");
        override = dir.resolve("override");

        write(base, "assets/demo/lang/en_us.json", "{\"item.demo.gem\":\"Gem\",\"item.demo.named\":\"%s's Thing\","
                + "\"item.demo.dust\":\"%s Dust\",\"material.demo.sodium\":\"Sodium\","
                + "\"item.demo.pair\":\"%2$s of %1$s\"}");
        write(base, "assets/demo/lang/zh_cn.json",
                "{\"item.demo.gem\":\"宝石\",\"item.demo.dust\":\"%s粉\",\"material.demo.sodium\":\"钠\"}");
        write(override, "assets/demo/lang/en_us.json", "{\"item.demo.gem\":\"Shiny Gem\"}");

        // Flat item inheriting from vanilla item/generated, which the pack does not contain.
        write(base, "assets/demo/models/item/gem.json", "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"demo:item/gem\"}}");
        png(base, "assets/demo/textures/item/gem.png", solid(16, 16, RED));

        // Block item inheriting from vanilla block/cube_column via a block model.
        write(base, "assets/demo/models/item/log.json", "{\"parent\":\"demo:block/log\"}");
        write(base, "assets/demo/models/block/log.json",
                "{\"parent\":\"block/cube_column\",\"textures\":{\"end\":\"demo:block/log_top\",\"side\":\"demo:block/log\"}}");
        png(base, "assets/demo/textures/block/log_top.png", solid(16, 16, GREEN));
        // Animated texture: 16x32 strip, only the first frame (blue) must be used.
        BufferedImage strip = solid(16, 32, BLUE);
        for (int y = 16; y < 32; y++) {
            for (int x = 0; x < 16; x++) {
                strip.setRGB(x, y, RED);
            }
        }
        png(base, "assets/demo/textures/block/log.png", strip);

        write(base, "assets/demo/models/item/chest.json", "{\"parent\":\"builtin/entity\"}");
        png(base, "assets/demo/textures/block/goo_still.png", solid(16, 16, GREEN));

        // Client-rendered item that still names the texture used for its break particles.
        write(base, "assets/demo/models/item/bed.json",
                "{\"parent\":\"builtin/entity\",\"textures\":{\"particle\":\"demo:block/log_top\"}}");

        // Custom model loader: nothing to draw at the top level, one ordinary model per variant. The first
        // variant names its model inline, the second by id, as real loaders do both.
        write(base, "assets/demo/models/item/press.json", "{\"parent\":\"demo:block/press\"}");
        write(base, "assets/demo/models/block/press.json", """
                {"parent":"minecraft:block/block","loader":"demo:machine","machine":"demo:press","variants":{
                  "state=idle":{"model":{"parent":"block/cube_all","textures":{"all":"demo:block/log_top"}}},
                  "state=busy":{"model":"demo:block/press_busy"}}}""");
        write(base, "assets/demo/models/block/press_busy.json",
                "{\"parent\":\"block/cube_all\",\"textures\":{\"all\":\"demo:block/log\"}}");

        // Custom loader whose own textures, under names this renderer knows nothing about, are the only
        // thing to go by. The first of them is the blank overlay the loader fills in at runtime.
        write(base, "assets/demo/models/item/tank.json",
                "{\"loader\":\"demo:fluid_container\",\"parent\":\"demo:item/missing\","
                        + "\"textures\":{\"contents\":\"demo:item/blank\",\"shell\":\"demo:item/gem\"}}");
        png(base, "assets/demo/textures/item/blank.png", solid(16, 16, 0));

        // Block item without an item model of its own: only the blockstate points at the block model.
        write(base, "assets/demo/blockstates/pillar.json", "{\"variants\":{\"\":{\"model\":\"demo:block/log\"}}}");

        // A mod that ships a texture and no model at all.
        png(base, "assets/demo/textures/item/nugget.png", solid(16, 16, GREEN));

        // Tinted faces: a mod's tint colour is not knowable, so its textures must be left alone.
        write(base, "assets/demo/models/item/vine.json", """
                {"elements":[{"from":[0,0,0],"to":[16,16,16],"faces":{
                  "up":{"texture":"demo:block/log_top","tintindex":0}}}]}""");
    }

    @Test
    void laterPacksOverrideAndTraversalIsRejected() {
        AssetLibrary library = library();
        assertEquals(List.of("demo"), List.copyOf(library.namespaces()));
        assertTrue(library.read("demo", "../../secret").isEmpty());
        assertTrue(library.read("demo", "/etc/passwd").isEmpty());
        assertEquals(2, library.readAll("demo", "lang/en_us.json").size());
    }

    @Test
    void resolvesNamesThroughLocalesAndFallbacks() {
        ResourceNames names = new ResourceNames(new LanguageTables(library()));
        assertEquals("Shiny Gem", names.displayName(translated("gem", "item.demo.gem"), "en_us"));
        assertEquals("宝石", names.displayName(translated("gem", "item.demo.gem"), "zh_cn"));
        assertEquals("Shiny Gem", names.displayName(translated("gem", "item.demo.gem"), "fr_fr"));
        assertEquals("'s Thing", names.displayName(translated("named", "item.demo.named"), "en_us"));
        assertEquals("Raw Iron Ore", names.displayName(translated("ores/raw_iron_ore", "item.demo.missing"), "en_us"));
        assertEquals("Bob's Sword",
                names.displayName(descriptor("gem", "item.demo.gem", ResourceText.literal("Bob's Sword")), "en_us"));
    }

    @Test
    void composesNamesFromTranslationArguments() {
        ResourceNames names = new ResourceNames(new LanguageTables(library()));
        // A shared key with the material as its argument: the argument must not be dropped.
        ResourceDescriptor dust = descriptor("sodium_dust", "item.demo.dust", ResourceText.translatable(
                "item.demo.dust", List.of(ResourceText.translatable("material.demo.sodium", List.of()))));
        assertEquals("Sodium Dust", names.displayName(dust, "en_us"));
        assertEquals("钠粉", names.displayName(dust, "zh_cn"));

        // Indexed arguments, which translators reorder.
        ResourceDescriptor pair = descriptor("pair", "item.demo.pair", ResourceText.translatable("item.demo.pair",
                List.of(ResourceText.literal("Iron"), ResourceText.literal("Block"))));
        assertEquals("Block of Iron", names.displayName(pair, "en_us"));

        // An untranslated key still shows what its arguments say.
        ResourceDescriptor unknown = descriptor("x", "item.demo.missing", ResourceText.translatable(
                "item.demo.missing", List.of(ResourceText.translatable("material.demo.sodium", List.of()))));
        assertEquals("Sodium", names.displayName(unknown, "en_us"));
    }

    @Test
    void turnsStylingIntoSpansAndNeverShowsFormattingCodes() {
        ResourceNames names = new ResourceNames(new LanguageTables(library()));

        // Legacy codes inside a name: a colour, bold, and a trailing reset.
        ResourceDescriptor legendary = descriptor("grinder", "item.demo.grinder",
                ResourceText.literal("\u00a79\u00a7l传奇研磨机\u00a7r"));
        assertEquals("传奇研磨机", names.displayName(legendary, "en_us"));
        List<NameSpan> spans = names.spans(legendary, "en_us");
        assertEquals(1, spans.size());
        assertEquals("传奇研磨机", spans.get(0).text());
        assertEquals("#5555FF", spans.get(0).color());
        assertTrue(spans.get(0).bold());

        // A trailing reset alone leaves an ordinary unstyled name.
        ResourceDescriptor basic = descriptor("basic", "item.demo.basic", ResourceText.literal("基础研磨机\u00a7r"));
        assertEquals("基础研磨机", names.displayName(basic, "en_us"));
        assertEquals(1, names.spans(basic, "en_us").size());
        assertFalse(names.spans(basic, "en_us").get(0).styled());

        // Styles carried by the component itself, inherited by its parts.
        ResourceDescriptor styled = descriptor("styled", "item.demo.styled",
                ResourceText.literal("Hot ").styled("#FF0000", true, null, null, null)
                        .withExtra(List.of(ResourceText.literal("Rod"))));
        List<NameSpan> hot = names.spans(styled, "en_us");
        assertEquals("Hot Rod", names.displayName(styled, "en_us"));
        assertEquals(1, hot.size(), "the part inherits the style, so both runs merge");
        assertEquals("#FF0000", hot.get(0).color());
    }

    @Test
    void rendersFlatItemsFromLayers() {
        BufferedImage icon = new IconRenderer(library()).render("item/demo/gem").orElseThrow();
        assertEquals(IconRenderer.SIZE, icon.getWidth());
        assertEquals(RED, icon.getRGB(32, 32));
    }

    @Test
    void rendersBlocksIsometricallyWithShadedSidesAndFirstAnimationFrame() {
        BufferedImage icon = new IconRenderer(library()).render("item/demo/log").orElseThrow();
        // Center of the top face: the green end texture at full brightness.
        assertEquals(GREEN, icon.getRGB(32, 16));
        // Lower left: north side, blue first frame shaded to 80%.
        int left = icon.getRGB(20, 44);
        assertEquals(0xFF, left >>> 24);
        assertEquals(Math.round(255 * 0.8f), left & 0xFF);
        assertEquals(0, (left >> 16) & 0xFF, "no red from the second animation frame");
        // Lower right: west side shaded to 60%.
        assertEquals(Math.round(255 * 0.6f), icon.getRGB(44, 44) & 0xFF);
        // Corners stay transparent.
        assertEquals(0, icon.getRGB(1, 1) >>> 24);
    }

    @Test
    void rendersFluidsAndSkipsClientOnlyModels() {
        IconRenderer renderer = new IconRenderer(library());
        assertTrue(renderer.render("fluid/demo/goo").isPresent());
        assertTrue(renderer.render("item/demo/chest").isEmpty());
        assertTrue(renderer.render("item/demo/does_not_exist").isEmpty());
        assertTrue(renderer.render("other/demo/x").isEmpty());
    }

    @Test
    void rendersModelsEmbeddedInCustomLoaders() {
        IconRenderer renderer = new IconRenderer(library());
        // The first variant's inline model, drawn as a block: green top face.
        assertEquals(GREEN, renderer.render("item/demo/press").orElseThrow().getRGB(32, 16));
    }

    @Test
    void fallsBackToTheTexturesAModelNames() {
        IconRenderer renderer = new IconRenderer(library());
        // A loader's own textures, in declaration order, skipping the blank one it fills in at runtime.
        assertEquals(RED, renderer.render("item/demo/tank").orElseThrow().getRGB(32, 32));
        // Client-rendered item: its particle texture beats a placeholder.
        assertEquals(GREEN, renderer.render("item/demo/bed").orElseThrow().getRGB(32, 32));
        // No model anywhere, only a texture named after the item.
        assertEquals(GREEN, renderer.render("item/demo/nugget").orElseThrow().getRGB(32, 32));
    }

    @Test
    void findsBlockModelsThroughBlockStatesAndLeavesModdedTintsAlone() {
        IconRenderer renderer = new IconRenderer(library());
        // demo:pillar has no item or block model of its own; its blockstate names one.
        assertEquals(GREEN, renderer.render("item/demo/pillar").orElseThrow().getRGB(32, 16));
        // A tinted face in a mod's namespace keeps its own colours: the tint comes from client code.
        assertEquals(GREEN, renderer.render("item/demo/vine").orElseThrow().getRGB(32, 16));
    }

    @Test
    void prefersIconsTheClientRendered() throws Exception {
        Path exported = dir.resolve("exported");
        png(exported, "assets/demo/mecc_icons/item/gem.png", solid(64, 64, BLUE));
        png(exported, "assets/demo/mecc_icons/fluid/goo.png", solid(64, 64, RED));
        IconRenderer renderer = new IconRenderer(AssetLibrary.scan(List.of(
                new AssetPack("base", base, "1"), new AssetPack("exported", exported, "1"))));

        assertEquals(BLUE, renderer.render("item/demo/gem").orElseThrow().getRGB(32, 32));
        assertEquals(RED, renderer.render("fluid/demo/goo").orElseThrow().getRGB(32, 32));
        // Anything the client did not export is still rendered from the model.
        assertEquals(GREEN, renderer.render("item/demo/log").orElseThrow().getRGB(32, 16));
    }

    @Test
    void servesClientRenderedIconsUntouched() throws Exception {
        // Stands in for an animated PNG: re-encoding it would keep only the first frame.
        Path exported = dir.resolve("exported");
        png(exported, "assets/demo/mecc_icons/item/gem.png", solid(64, 64, BLUE));
        byte[] original = Files.readAllBytes(exported.resolve("assets/demo/mecc_icons/item/gem.png"));
        // Not a PNG at all: ignored, and the model is rendered instead.
        write(exported, "assets/demo/mecc_icons/item/log.png", "<svg/>");
        AssetLibrary library = AssetLibrary.scan(List.of(new AssetPack("base", base, "1"), new AssetPack("exported", exported, "1")));

        var executor = Executors.newFixedThreadPool(1);
        try {
            DefaultIconService service = new DefaultIconService(() -> library, executor);
            assertArrayEquals(original, service.icon("item/demo/gem").toCompletableFuture().get(5, TimeUnit.SECONDS).orElseThrow().png());
            byte[] log = service.icon("item/demo/log").toCompletableFuture().get(5, TimeUnit.SECONDS).orElseThrow().png();
            assertEquals(GREEN, ImageIO.read(new ByteArrayInputStream(log)).getRGB(32, 16));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void iconServiceCachesPerAssetVersion() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            AssetLibrary library = library();
            DefaultIconService service = new DefaultIconService(() -> library, executor);
            IconImage first = service.icon("item/demo/gem").toCompletableFuture().get(5, TimeUnit.SECONDS).orElseThrow();
            IconImage second = service.icon("item/demo/gem").toCompletableFuture().get(5, TimeUnit.SECONDS).orElseThrow();
            assertArrayEquals(first.png(), second.png());
            assertEquals(RED, ImageIO.read(new ByteArrayInputStream(first.png())).getRGB(10, 10));
            assertTrue(service.icon("item/../gem").toCompletableFuture().get(5, TimeUnit.SECONDS).isEmpty());
            assertTrue(service.icon("item/demo/missing").toCompletableFuture().get(5, TimeUnit.SECONDS).isEmpty());

            AssetLibrary other = AssetLibrary.scan(List.of(new AssetPack("base", base, "v2")));
            assertNotEquals(library.version(), other.version());
        } finally {
            executor.shutdownNow();
        }
    }

    private AssetLibrary library() {
        return AssetLibrary.scan(List.of(new AssetPack("base", base, "1"), new AssetPack("override", override, "1")));
    }

    private static ResourceDescriptor descriptor(String path, String key, ResourceText name) {
        return new ResourceDescriptor(ResourceId.of("item", "demo", path), key, name, "demo", null, "item/demo/" + path);
    }

    /** The common case: an item whose name is just its own translation key. */
    private static ResourceDescriptor translated(String path, String key) {
        return descriptor(path, key, ResourceText.translatable(key, List.of()));
    }

    private static void write(Path root, String path, String content) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void png(Path root, String path, BufferedImage image) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        ImageIO.write(image, "png", file.toFile());
    }

    private static BufferedImage solid(int width, int height, int argb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, argb);
            }
        }
        return image;
    }
}
