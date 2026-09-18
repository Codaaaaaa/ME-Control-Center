package io.github.codaaaaaa.mecc.web.staticfiles;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The web UI bundle, loaded fully into memory at startup.
 *
 * <p>The build writes {@value #INDEX_FILE} listing every file under {@value #ROOT}. Reading an
 * explicit index (instead of listing a directory) works for any class loader or virtual file
 * system, including Forge's {@code union:} jar file system.
 */
public final class StaticAssets {
    public static final String ROOT = "mecc-web/";
    public static final String INDEX_FILE = ROOT + "asset-index.txt";

    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("webmanifest", "application/manifest+json; charset=utf-8"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("webp", "image/webp"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("woff", "font/woff"));

    private final Map<String, Asset> assets;

    private StaticAssets(Map<String, Asset> assets) {
        this.assets = Collections.unmodifiableMap(assets);
    }

    public static StaticAssets empty() {
        return new StaticAssets(Map.of());
    }

    /** Loads every file listed in the bundle index. Returns an empty bundle when no index exists. */
    public static StaticAssets load(StaticAssetSource source) throws IOException {
        Optional<InputStream> index = source.open(INDEX_FILE);
        if (index.isEmpty()) {
            return empty();
        }
        Map<String, Asset> assets = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(index.get(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String path = line.strip();
                if (path.isEmpty()) {
                    continue;
                }
                if (path.startsWith("/") || path.contains("..") || path.contains("\\")) {
                    throw new IOException("Invalid path in web UI asset index: " + path);
                }
                try (InputStream in = source.open(ROOT + path)
                        .orElseThrow(() -> new IOException("Web UI asset listed in index is missing: " + path))) {
                    assets.put(path, Asset.of(path, in.readAllBytes()));
                }
            }
        }
        return new StaticAssets(assets);
    }

    public static StaticAssets of(Map<String, byte[]> files) {
        Map<String, Asset> assets = new LinkedHashMap<>();
        files.forEach((path, bytes) -> assets.put(path, Asset.of(path, bytes)));
        return new StaticAssets(assets);
    }

    public Optional<Asset> get(String path) {
        return Optional.ofNullable(assets.get(path));
    }

    public boolean isEmpty() {
        return assets.isEmpty();
    }

    public int size() {
        return assets.size();
    }

    /** One bundled file. {@code bytes} must not be modified. */
    public record Asset(String path, byte[] bytes, String contentType, String etag) {
        static Asset of(String path, byte[] bytes) {
            return new Asset(path, bytes, contentTypeFor(path), etagFor(bytes));
        }
    }

    static String contentTypeFor(String path) {
        int dot = path.lastIndexOf('.');
        String extension = dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
        return CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");
    }

    private static String etagFor(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return "\"" + HexFormat.of().formatHex(digest, 0, 12) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
