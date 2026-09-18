package io.github.codaaaaaa.mecc.runtime.assets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.codaaaaaa.mecc.core.assets.AssetPack;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vanilla Minecraft client assets, which dedicated servers do not include.
 *
 * <p>Layout under {@code config/mecc/cache/vanilla/<version>/}: {@code client.jar} (textures, models,
 * English names) and {@code lang/assets/minecraft/lang/<locale>.json} (other languages). Admins may place
 * these files manually; with {@code [assets] download_vanilla_assets = true} they are downloaded from
 * Mojang's official servers and verified against the published SHA-1 hashes.
 */
public final class VanillaAssets {
    private static final Logger LOGGER = LoggerFactory.getLogger(VanillaAssets.class);
    private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String RESOURCES = "https://resources.download.minecraft.net/";
    private static final Pattern VERSION = Pattern.compile("^[0-9A-Za-z._\\-]+$");
    private static final Pattern SHA1 = Pattern.compile("^[0-9a-f]{40}$");
    static final List<String> EXTRA_LOCALES = List.of("zh_cn");

    private final Path directory;
    private final String minecraftVersion;
    private final ObjectMapper json = new ObjectMapper();

    public VanillaAssets(Path cacheDirectory, String minecraftVersion) {
        if (!VERSION.matcher(minecraftVersion).matches()) {
            throw new IllegalArgumentException("Unexpected Minecraft version: " + minecraftVersion);
        }
        this.directory = cacheDirectory.resolve("vanilla").resolve(minecraftVersion);
        this.minecraftVersion = minecraftVersion;
    }

    private Path clientJar() {
        return directory.resolve("client.jar");
    }

    private Path languageRoot() {
        return directory.resolve("lang");
    }

    public boolean present() {
        return Files.isRegularFile(clientJar());
    }

    /** Asset packs for whatever vanilla files exist. Opened archives are added to {@code archives} for closing. */
    List<AssetPack> packs(List<FileSystem> archives) {
        List<AssetPack> packs = new ArrayList<>();
        try {
            if (present()) {
                FileSystem jar = FileSystems.newFileSystem(clientJar());
                archives.add(jar);
                packs.add(new AssetPack("minecraft-" + minecraftVersion + "-client", jar.getPath("/"),
                        Long.toString(Files.size(clientJar()))));
            }
            if (Files.isDirectory(languageRoot().resolve("assets"))) {
                packs.add(new AssetPack("minecraft-" + minecraftVersion + "-languages", languageRoot(),
                        Long.toString(Files.getLastModifiedTime(languageRoot()).toMillis())));
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not open vanilla assets in {}: {}", directory, e.toString());
        }
        return packs;
    }

    /**
     * Downloads missing files. Blocking; call on a worker thread.
     *
     * @return whether anything new was downloaded
     */
    public boolean downloadMissing() throws IOException, InterruptedException {
        List<Path> wanted = new ArrayList<>();
        if (!present()) {
            wanted.add(clientJar());
        }
        for (String locale : EXTRA_LOCALES) {
            Path file = languageFile(locale);
            if (!Files.isRegularFile(file)) {
                wanted.add(file);
            }
        }
        if (wanted.isEmpty()) {
            return false;
        }
        LOGGER.info("Downloading vanilla Minecraft {} assets from Mojang for ME Control Center icons and names", minecraftVersion);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL).build();

        JsonNode manifest = getJson(http, MANIFEST);
        JsonNode entry = null;
        for (JsonNode version : manifest.path("versions")) {
            if (minecraftVersion.equals(version.path("id").asText())) {
                entry = version;
            }
        }
        if (entry == null) {
            throw new IOException("Minecraft version " + minecraftVersion + " not found in Mojang's version manifest");
        }
        JsonNode versionJson = getJson(http, entry.path("url").asText());
        if (!present()) {
            JsonNode client = versionJson.path("downloads").path("client");
            download(http, client.path("url").asText(), client.path("sha1").asText(), clientJar());
        }
        JsonNode index = getJson(http, versionJson.path("assetIndex").path("url").asText());
        for (String locale : EXTRA_LOCALES) {
            Path file = languageFile(locale);
            if (Files.isRegularFile(file)) {
                continue;
            }
            String hash = index.path("objects").path("minecraft/lang/" + locale + ".json").path("hash").asText();
            if (!SHA1.matcher(hash).matches()) {
                LOGGER.warn("Mojang asset index has no {} language file", locale);
                continue;
            }
            download(http, RESOURCES + hash.substring(0, 2) + "/" + hash, hash, file);
        }
        LOGGER.info("Vanilla Minecraft assets ready in {}", directory);
        return true;
    }

    private Path languageFile(String locale) {
        return languageRoot().resolve("assets/minecraft/lang/" + locale + ".json");
    }

    private JsonNode getJson(HttpClient http, String url) throws IOException, InterruptedException {
        requireMojangUrl(url);
        HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }
        return json.readTree(response.body());
    }

    private static void download(HttpClient http, String url, String sha1, Path target) throws IOException, InterruptedException {
        requireMojangUrl(url);
        if (!SHA1.matcher(sha1).matches()) {
            throw new IOException("Missing checksum for " + url);
        }
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".part");
        HttpResponse<InputStream> response = http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }
        try (InputStream in = response.body()) {
            Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(temporary)));
            if (!actual.equals(sha1)) {
                throw new IOException("Checksum mismatch for " + url);
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 unavailable", e);
        } catch (IOException e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void requireMojangUrl(String url) throws IOException {
        URI uri = URI.create(url);
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null
                || !(host.endsWith(".mojang.com") || host.endsWith(".minecraft.net"))) {
            throw new IOException("Refusing to download from unexpected location: " + url);
        }
    }
}
