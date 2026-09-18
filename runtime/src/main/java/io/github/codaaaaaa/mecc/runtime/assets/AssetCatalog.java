package io.github.codaaaaaa.mecc.runtime.assets;

import io.github.codaaaaaa.mecc.assets.AssetLibrary;
import io.github.codaaaaaa.mecc.assets.LanguageTables;
import io.github.codaaaaaa.mecc.assets.ResourceNames;
import io.github.codaaaaaa.mecc.core.assets.AssetPack;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The server's current asset library, assembled from (lowest to highest priority): vanilla client assets
 * (if present), platform packs (mod files), content packs from the Client Exporter in
 * {@code config/mecc/content-packs/}, and admin-provided resource packs in {@code config/mecc/resourcepacks/}.
 * Rebuilt when sources change; readers always see a complete library.
 */
public final class AssetCatalog implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(AssetCatalog.class);

    private final List<AssetPack> platformPacks;
    private final VanillaAssets vanilla;
    private final ContentPacks contentPacks;
    private final Path resourcePackDirectory;
    private final List<FileSystem> openArchives = new ArrayList<>();
    private volatile AssetLibrary library = AssetLibrary.empty();
    private volatile ResourceNames names = new ResourceNames(new LanguageTables(AssetLibrary.empty()));

    public AssetCatalog(List<AssetPack> platformPacks, VanillaAssets vanilla, ContentPacks contentPacks,
                        Path resourcePackDirectory) {
        this.platformPacks = List.copyOf(platformPacks);
        this.vanilla = vanilla;
        this.contentPacks = contentPacks;
        this.resourcePackDirectory = resourcePackDirectory;
    }

    public AssetLibrary library() {
        return library;
    }

    public ResourceNames names() {
        return names;
    }

    /** Scans all sources. Blocking I/O; call on a worker thread. */
    public synchronized void rebuild() {
        closeArchives();
        List<AssetPack> packs = new ArrayList<>(vanilla.packs(openArchives));
        packs.addAll(platformPacks);
        // Rendered by the real client, so better than anything the mods' files alone give; an admin's own
        // resource packs still have the last word.
        packs.addAll(contentPacks.packs(openArchives));
        packs.addAll(resourcePacks());
        AssetLibrary rebuilt = AssetLibrary.scan(packs);
        names = new ResourceNames(new LanguageTables(rebuilt));
        library = rebuilt;
        LOGGER.info("ME Control Center assets: {} packs, {} namespaces{}", packs.size(), rebuilt.namespaces().size(),
                vanilla.present() ? "" : " (vanilla icons unavailable; see [assets] download_vanilla_assets)");
    }

    /**
     * Downloads missing vanilla assets and reloads if anything arrived.
     *
     * @return whether the library was rebuilt
     */
    public boolean downloadVanillaAssets() throws IOException, InterruptedException {
        if (!vanilla.downloadMissing()) {
            return false;
        }
        rebuild();
        return true;
    }

    private List<AssetPack> resourcePacks() {
        List<AssetPack> packs = new ArrayList<>();
        if (!Files.isDirectory(resourcePackDirectory)) {
            return packs;
        }
        try (Stream<Path> entries = Files.list(resourcePackDirectory)) {
            for (Path entry : entries.sorted().toList()) {
                String name = entry.getFileName().toString();
                try {
                    if (Files.isDirectory(entry)) {
                        packs.add(new AssetPack("resourcepack/" + name, entry, Long.toString(Files.getLastModifiedTime(entry).toMillis())));
                    } else if (name.endsWith(".zip") || name.endsWith(".jar")) {
                        FileSystem archive = FileSystems.newFileSystem(entry);
                        openArchives.add(archive);
                        packs.add(new AssetPack("resourcepack/" + name, archive.getPath("/"),
                                Files.size(entry) + "-" + Files.getLastModifiedTime(entry).toMillis()));
                    }
                } catch (IOException | RuntimeException e) {
                    LOGGER.warn("Skipping ME Control Center resource pack {}: {}", entry, e.toString());
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Could not list {}: {}", resourcePackDirectory, e.toString());
        }
        return packs;
    }

    private void closeArchives() {
        for (FileSystem archive : openArchives) {
            try {
                archive.close();
            } catch (IOException | RuntimeException e) {
                LOGGER.debug("Error closing asset archive", e);
            }
        }
        openArchives.clear();
    }

    @Override
    public synchronized void close() {
        closeArchives();
    }
}
