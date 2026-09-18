package io.github.codaaaaaa.mecc.assets;

import io.github.codaaaaaa.mecc.core.assets.AssetPack;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read-only view over asset packs with resource-pack override semantics: a file in a later pack wins.
 * Thread-safe; file reads may happen concurrently.
 */
public final class AssetLibrary {
    private static final Logger LOGGER = LoggerFactory.getLogger(AssetLibrary.class);
    private static final Pattern NAMESPACE = Pattern.compile("^[a-z0-9_.\\-]+$");
    private static final Pattern PATH = Pattern.compile("^[a-z0-9_.\\-/]+$");

    private final List<AssetPack> packs;
    /** Namespace to the packs containing it, highest priority first. */
    private final Map<String, List<AssetPack>> byNamespace;
    private final String version;

    private AssetLibrary(List<AssetPack> packs, Map<String, List<AssetPack>> byNamespace, String version) {
        this.packs = packs;
        this.byNamespace = byNamespace;
        this.version = version;
    }

    public static AssetLibrary empty() {
        return scan(List.of());
    }

    /** Indexes which namespaces each pack provides. Blocking I/O; call off the server thread. */
    public static AssetLibrary scan(List<AssetPack> packs) {
        Map<String, List<AssetPack>> byNamespace = new LinkedHashMap<>();
        for (AssetPack pack : packs) {
            Path assets = pack.root().resolve("assets");
            if (!Files.isDirectory(assets)) {
                continue;
            }
            try (Stream<Path> children = Files.list(assets)) {
                children.filter(Files::isDirectory)
                        .map(child -> stripSlash(child.getFileName().toString()))
                        .filter(name -> NAMESPACE.matcher(name).matches())
                        .forEach(namespace -> byNamespace.computeIfAbsent(namespace, n -> new ArrayList<>()).add(0, pack));
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Could not index assets of {}: {}", pack.id(), e.toString());
            }
        }
        byNamespace.replaceAll((namespace, list) -> List.copyOf(list));
        return new AssetLibrary(List.copyOf(packs), Collections.unmodifiableMap(byNamespace), fingerprint(packs));
    }

    public List<AssetPack> packs() {
        return packs;
    }

    public Set<String> namespaces() {
        return byNamespace.keySet();
    }

    /** Short hash identifying the installed asset set. */
    public String version() {
        return version;
    }

    /**
     * Reads {@code assets/<namespace>/<path>} from the highest-priority pack that has it.
     * Invalid or traversing paths are treated as missing.
     */
    public Optional<byte[]> read(String namespace, String path) {
        if (!valid(namespace, path)) {
            return Optional.empty();
        }
        for (AssetPack pack : byNamespace.getOrDefault(namespace, List.of())) {
            Optional<byte[]> bytes = readFrom(pack, namespace, path);
            if (bytes.isPresent()) {
                return bytes;
            }
        }
        return Optional.empty();
    }

    /** Reads the file from every pack that has it, lowest priority first (for merging, e.g. language files). */
    public List<byte[]> readAll(String namespace, String path) {
        if (!valid(namespace, path)) {
            return List.of();
        }
        List<byte[]> result = new ArrayList<>();
        List<AssetPack> candidates = byNamespace.getOrDefault(namespace, List.of());
        for (int i = candidates.size() - 1; i >= 0; i--) {
            readFrom(candidates.get(i), namespace, path).ifPresent(result::add);
        }
        return result;
    }

    private static Optional<byte[]> readFrom(AssetPack pack, String namespace, String path) {
        Path file = pack.root().resolve("assets").resolve(namespace).resolve(path);
        try {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            long size = Files.size(file);
            if (size > 16L * 1024 * 1024) {
                LOGGER.debug("Skipping oversized asset {} in {}", file, pack.id());
                return Optional.empty();
            }
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Could not read asset {} from {}: {}", path, pack.id(), e.toString());
            return Optional.empty();
        }
    }

    static boolean valid(String namespace, String path) {
        return namespace != null && path != null
                && NAMESPACE.matcher(namespace).matches() && PATH.matcher(path).matches()
                && !path.startsWith("/") && !path.contains("..");
    }

    private static String stripSlash(String name) {
        return name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
    }

    private static String fingerprint(List<AssetPack> packs) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (AssetPack pack : packs) {
                digest.update(pack.id().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(pack.version().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            // Bump when the renderer changes so browsers do not keep icons rendered by an older version.
            digest.update(IconRenderer.RENDERER_VERSION.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest(), 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
