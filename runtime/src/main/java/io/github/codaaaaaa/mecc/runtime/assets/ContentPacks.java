package io.github.codaaaaaa.mecc.runtime.assets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.codaaaaaa.mecc.assets.ContentPackLayout;
import io.github.codaaaaaa.mecc.core.assets.AssetPack;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Content packs made by the optional Client Exporter (spec section 19), in {@code config/mecc/content-packs/}
 * as zip files or unpacked directories. Read as data only, like any other asset pack.
 *
 * <p>A pack from a different modpack version still works for everything that did not change, so a mismatch
 * (spec section 45) is reported rather than refused.
 */
public final class ContentPacks {
    private static final Logger LOGGER = LoggerFactory.getLogger(ContentPacks.class);
    /** Mods whose versions say nothing about whether icons and names still match. */
    private static final Set<String> IGNORED_MODS = Set.of("minecraft", "forge", "mecc", "mecc_exporter");
    private static final int LISTED = 5;

    private final Path directory;
    private final String minecraftVersion;
    private final Map<String, String> serverMods;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * @param serverMods mod id to version on this server; empty if the platform cannot tell, which skips the
     *                   comparison
     */
    public ContentPacks(Path directory, String minecraftVersion, Map<String, String> serverMods) {
        this.directory = directory;
        this.minecraftVersion = minecraftVersion;
        this.serverMods = Map.copyOf(serverMods);
    }

    /** Opens every valid pack, in name order. Opened archives are added to {@code archives} for closing. */
    List<AssetPack> packs(List<FileSystem> archives) {
        List<AssetPack> packs = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return packs;
        }
        try (Stream<Path> entries = Files.list(directory)) {
            for (Path entry : entries.sorted().toList()) {
                String name = entry.getFileName().toString();
                try {
                    if (Files.isDirectory(entry)) {
                        open(name, entry, Long.toString(Files.getLastModifiedTime(entry).toMillis())).ifPresent(packs::add);
                    } else if (name.endsWith(".zip")) {
                        FileSystem archive = FileSystems.newFileSystem(entry);
                        Optional<AssetPack> pack;
                        try {
                            pack = open(name, archive.getPath("/"),
                                    Files.size(entry) + "-" + Files.getLastModifiedTime(entry).toMillis());
                        } catch (IOException | RuntimeException e) {
                            archive.close();
                            throw e;
                        }
                        if (pack.isPresent()) {
                            archives.add(archive);
                            packs.add(pack.get());
                        } else {
                            archive.close();
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    LOGGER.warn("Skipping ME Control Center content pack {}: {}", entry, e.toString());
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Could not list {}: {}", directory, e.toString());
        }
        return packs;
    }

    private Optional<AssetPack> open(String name, Path root, String version) throws IOException {
        Path manifestFile = root.resolve(ContentPackLayout.MANIFEST);
        JsonNode manifest = Files.isRegularFile(manifestFile) ? json.readTree(Files.readAllBytes(manifestFile)) : null;
        String problem = manifest == null || !manifest.isObject()
                ? "not an ME Control Center content pack (no " + ContentPackLayout.MANIFEST + ")"
                : manifest.path("format").asInt(0) > ContentPackLayout.FORMAT_VERSION
                        ? "made by a newer ME Control Center Client Exporter (format " + manifest.path("format").asInt() + ")"
                        : null;
        if (problem != null) {
            LOGGER.warn("Skipping {} in {}: {}", name, directory, problem);
            return Optional.empty();
        }
        report(name, manifest);
        return Optional.of(new AssetPack("contentpack/" + name, root, version));
    }

    private void report(String name, JsonNode manifest) {
        String packMinecraft = manifest.path("minecraft").asText("?");
        String summary = "%s (%s icons, languages %s, made %s)".formatted(name,
                manifest.path("icons").path("exported").asText("?"), manifest.path("locales"), manifest.path("createdAt").asText("?"));
        List<String> problems = new ArrayList<>();
        if (!packMinecraft.equals(minecraftVersion)) {
            problems.add("it was made for Minecraft " + packMinecraft + ", this server runs " + minecraftVersion);
        }
        if (!serverMods.isEmpty()) {
            Map<String, String> packMods = new TreeMap<>();
            for (JsonNode mod : manifest.path("mods")) {
                packMods.put(mod.path("id").asText(), mod.path("version").asText());
            }
            List<String> changed = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            new TreeMap<>(serverMods).forEach((id, serverVersion) -> {
                if (IGNORED_MODS.contains(id)) {
                    return;
                }
                String packVersion = packMods.get(id);
                if (packVersion == null) {
                    missing.add(id);
                } else if (!packVersion.equals(serverVersion)) {
                    changed.add(id + " " + packVersion + " -> " + serverVersion);
                }
            });
            // Mods only the client has (maps, shaders, the exporter itself) are normal and not mentioned.
            if (!changed.isEmpty()) {
                problems.add(changed.size() + " mods have other versions on the server: " + list(changed));
            }
            if (!missing.isEmpty()) {
                problems.add(missing.size() + " server mods were not installed when it was made: " + list(missing));
            }
        }
        if (problems.isEmpty()) {
            LOGGER.info("ME Control Center content pack {} matches this server", summary);
        } else {
            LOGGER.warn("ME Control Center content pack {} is from a different installation; its icons and names are used where "
                    + "they still apply, but re-exporting from the current modpack is recommended: {}",
                    summary, String.join("; ", problems));
        }
    }

    private static String list(List<String> items) {
        return items.size() <= LISTED
                ? String.join(", ", items)
                : String.join(", ", items.subList(0, LISTED)) + ", ...";
    }
}
