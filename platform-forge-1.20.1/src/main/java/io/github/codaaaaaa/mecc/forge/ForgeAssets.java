package io.github.codaaaaaa.mecc.forge;

import io.github.codaaaaaa.mecc.core.assets.AssetPack;
import io.github.codaaaaaa.mecc.platform.AssetPlatform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.locating.IModFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asset sources of this installation: every loaded mod file, plus KubeJS assets, which modpacks use for
 * custom items. Files are read as data only; no client code is loaded (spec sections 19 and 20).
 */
final class ForgeAssets implements AssetPlatform {
    private static final Logger LOGGER = LoggerFactory.getLogger(ForgeAssets.class);

    @Override
    public List<AssetPack> assetPacks() {
        List<AssetPack> packs = new ArrayList<>();
        for (IModFileInfo info : ModList.get().getModFiles()) {
            IModFile file = info.getFile();
            try {
                Path root = file.getSecureJar().getRootPath();
                if (Files.isDirectory(root.resolve("assets"))) {
                    packs.add(new AssetPack("mod/" + file.getFileName(), root, version(file.getFilePath())));
                }
            } catch (RuntimeException e) {
                LOGGER.debug("Could not inspect assets of {}: {}", file, e.toString());
            }
        }
        Path kubeJs = FMLPaths.GAMEDIR.get().resolve("kubejs");
        if (Files.isDirectory(kubeJs.resolve("assets"))) {
            packs.add(new AssetPack("kubejs", kubeJs, version(kubeJs)));
        }
        return packs;
    }

    @Override
    public Map<String, String> modNames() {
        Map<String, String> names = new HashMap<>();
        ModList.get().getMods().forEach(mod -> names.put(mod.getModId(), mod.getDisplayName()));
        return Map.copyOf(names);
    }

    @Override
    public Map<String, String> modVersions() {
        Map<String, String> versions = new HashMap<>();
        ModList.get().getMods().forEach(mod -> versions.put(mod.getModId(), mod.getVersion().toString()));
        return Map.copyOf(versions);
    }

    private static String version(Path path) {
        try {
            return Files.size(path) + "-" + Files.getLastModifiedTime(path).toMillis();
        } catch (IOException | RuntimeException e) {
            return path.getFileName().toString();
        }
    }
}
