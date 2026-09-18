package io.github.codaaaaaa.mecc.assets;

import io.github.codaaaaaa.mecc.core.assets.AssetPack;
import java.io.File;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Manual visual check against real mod and game jars. Renders icons to a directory for inspection.
 *
 * <pre>
 * MECC_SMOKE_PACKS="client.jar;ae2.jar" MECC_SMOKE_OUT=/tmp/icons MECC_SMOKE_ICONS="item/ae2/controller,..." \
 *   ./gradlew :assets:test --tests '*RealAssetsSmokeTest'
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "MECC_SMOKE_PACKS", matches = ".+")
class RealAssetsSmokeTest {

    @Test
    void rendersIconsFromRealJars() throws Exception {
        List<AssetPack> packs = new ArrayList<>();
        List<FileSystem> systems = new ArrayList<>();
        for (String jar : System.getenv("MECC_SMOKE_PACKS").split(File.pathSeparator.equals(";") ? ";" : "[;:]")) {
            Path path = Path.of(jar);
            FileSystem fs = FileSystems.newFileSystem(URI.create("jar:" + path.toUri()), Map.of());
            systems.add(fs);
            packs.add(new AssetPack(path.getFileName().toString(), fs.getPath("/"), "1"));
        }
        try {
            AssetLibrary library = AssetLibrary.scan(packs);
            IconRenderer renderer = new IconRenderer(library);
            Path out = Path.of(System.getenv("MECC_SMOKE_OUT"));
            Files.createDirectories(out);
            for (String key : System.getenv("MECC_SMOKE_ICONS").split(",")) {
                var image = renderer.render(key.strip());
                System.out.println(key + " -> " + (image.isPresent() ? "rendered" : "none"));
                if (image.isPresent()) {
                    ImageIO.write(image.get(), "png", out.resolve(key.strip().replace('/', '_') + ".png").toFile());
                }
            }
            LanguageTables languages = new LanguageTables(library);
            System.out.println("en_us entries: " + languages.table("en_us").size() + ", zh_cn entries: " + languages.table("zh_cn").size());
        } finally {
            for (FileSystem fs : systems) {
                fs.close();
            }
        }
    }
}
