package io.github.codaaaaaa.mecc.runtime.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.codaaaaaa.mecc.assets.IconRenderer;
import io.github.codaaaaaa.mecc.assets.LanguageTables;
import io.github.codaaaaaa.mecc.core.assets.AssetPack;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContentPacksTest {
    private static final String MANIFEST = """
            {"format":1,"minecraft":"1.20.1","locales":["en_us","zh_cn"],"icons":{"exported":1},
             "mods":[{"id":"demo","version":"1.0"}]}""";

    @TempDir
    Path dir;

    @Test
    void importsIconsAndLanguagesFromExportedPacks() throws Exception {
        Path packs = dir.resolve("content-packs");
        zip(packs.resolve("mecc-content-pack-abc.zip"), Map.of(
                "mecc-content-pack.json", MANIFEST.getBytes(StandardCharsets.UTF_8),
                // Vanilla's Chinese names: what a dedicated server never has on its own.
                "assets/minecraft/lang/zh_cn.json", "{\"block.minecraft.stone\":\"石头\"}".getBytes(StandardCharsets.UTF_8),
                "assets/demo/mecc_icons/item/gem.png", png(0xFF0000FF)));

        AssetCatalog catalog = catalog(packs, Map.of("demo", "1.0"));
        try {
            catalog.rebuild();
            assertEquals("石头", new LanguageTables(catalog.library()).translate("zh_cn", "block.minecraft.stone").orElseThrow());
            assertEquals(0xFF0000FF, new IconRenderer(catalog.library()).render("item/demo/gem").orElseThrow().getRGB(32, 32));
        } finally {
            catalog.close();
        }
    }

    @Test
    void ignoresArchivesThatAreNotContentPacks() throws Exception {
        Path packs = dir.resolve("content-packs");
        zip(packs.resolve("random.zip"), Map.of("assets/demo/mecc_icons/item/gem.png", png(0xFF0000FF)));
        zip(packs.resolve("future.zip"), Map.of(
                "mecc-content-pack.json", "{\"format\":99}".getBytes(StandardCharsets.UTF_8),
                "assets/demo/mecc_icons/item/gem.png", png(0xFF0000FF)));
        zip(packs.resolve("broken.zip"), Map.of("mecc-content-pack.json", "{not json".getBytes(StandardCharsets.UTF_8)));
        Files.writeString(packs.resolve("notes.txt"), "not a pack");

        AssetCatalog catalog = catalog(packs, Map.of());
        try {
            catalog.rebuild();
            assertTrue(catalog.library().namespaces().isEmpty());
        } finally {
            catalog.close();
        }
    }

    @Test
    void usesPacksFromOtherInstallationsToo() throws Exception {
        // A different modpack version still has the right icons for everything that did not change; the
        // mismatch is logged, not enforced.
        Path packs = dir.resolve("content-packs");
        zip(packs.resolve("old.zip"), Map.of(
                "mecc-content-pack.json", MANIFEST.getBytes(StandardCharsets.UTF_8),
                "assets/demo/mecc_icons/item/gem.png", png(0xFF0000FF)));

        AssetCatalog catalog = catalog(packs, Map.of("demo", "2.0", "other", "1.0"));
        try {
            catalog.rebuild();
            assertFalse(new IconRenderer(catalog.library()).render("item/demo/gem").isEmpty());
        } finally {
            catalog.close();
        }
    }

    private AssetCatalog catalog(Path contentPacks, Map<String, String> serverMods) {
        return new AssetCatalog(List.<AssetPack>of(), new VanillaAssets(dir.resolve("cache"), "1.20.1"),
                new ContentPacks(contentPacks, "1.20.1", serverMods), dir.resolve("resourcepacks"));
    }

    private static void zip(Path file, Map<String, byte[]> entries) throws Exception {
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }

    private static byte[] png(int argb) throws Exception {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                image.setRGB(x, y, argb);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
