package io.github.codaaaaaa.mecc.assets;

import io.github.codaaaaaa.mecc.core.assets.IconService;
import io.github.codaaaaaa.mecc.core.error.ErrorCode;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders icons on a dedicated executor and caches the encoded PNGs (including "no icon" results) per
 * asset version. Concurrent requests for the same icon share one render.
 */
public final class DefaultIconService implements IconService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultIconService.class);
    private static final Pattern KEY = Pattern.compile("^(item|fluid|other)/[a-z0-9_.\\-]+/[a-z0-9_.\\-/]+$");
    static final int MAX_CACHED = 8_192;

    private final Supplier<AssetLibrary> library;
    private final Executor executor;
    private final Map<String, Optional<IconImage>> cache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Optional<IconImage>> eldest) {
            return size() > MAX_CACHED;
        }
    };
    private final Map<String, CompletableFuture<Optional<IconImage>>> inFlight = new ConcurrentHashMap<>();
    private volatile String cacheVersion = "";
    private volatile IconRenderer renderer;
    private volatile boolean imagingBroken;

    /**
     * @param library  current asset library; may change (e.g. after vanilla assets are downloaded)
     * @param executor render executor; should be small and bounded
     */
    public DefaultIconService(Supplier<AssetLibrary> library, Executor executor) {
        this.library = library;
        this.executor = executor;
    }

    @Override
    public String assetVersion() {
        return library.get().version();
    }

    @Override
    public CompletionStage<Optional<IconImage>> icon(String iconKey) {
        if (iconKey == null || !KEY.matcher(iconKey).matches() || iconKey.contains("..") || imagingBroken) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        AssetLibrary current = library.get();
        synchronized (cache) {
            if (!current.version().equals(cacheVersion)) {
                cache.clear();
                cacheVersion = current.version();
                renderer = new IconRenderer(current);
            }
            Optional<IconImage> cached = cache.get(iconKey);
            if (cached != null) {
                return CompletableFuture.completedFuture(cached);
            }
        }
        IconRenderer activeRenderer = renderer;
        String version = current.version();
        return inFlight.computeIfAbsent(version + "|" + iconKey, flightKey -> {
            CompletableFuture<Optional<IconImage>> future;
            try {
                future = CompletableFuture.supplyAsync(() -> render(activeRenderer, iconKey), executor);
            } catch (RejectedExecutionException e) {
                return CompletableFuture.failedFuture(new MeccException(ErrorCode.SERVICE_UNAVAILABLE,
                        "Too many icons are being rendered; try again shortly"));
            }
            return future.whenComplete((icon, error) -> {
                inFlight.remove(flightKey);
                if (error == null) {
                    synchronized (cache) {
                        if (version.equals(cacheVersion)) {
                            cache.put(iconKey, icon);
                        }
                    }
                }
            });
        });
    }

    private static IconImage image(byte[] png) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(png);
        return new IconImage(png, "\"" + HexFormat.of().formatHex(digest, 0, 12) + "\"");
    }

    private Optional<IconImage> render(IconRenderer activeRenderer, String iconKey) {
        try {
            Optional<byte[]> exported = activeRenderer.clientRenderedPng(iconKey);
            if (exported.isPresent()) {
                return Optional.of(image(exported.get()));
            }
            Optional<BufferedImage> image = activeRenderer.render(iconKey);
            if (image.isEmpty()) {
                return Optional.empty();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(2048);
            if (!ImageIO.write(image.get(), "png", out)) {
                return Optional.empty();
            }
            return Optional.of(image(out.toByteArray()));
        } catch (LinkageError e) {
            // e.g. a Java runtime without java.desktop: icons are unavailable, the terminal still works.
            imagingBroken = true;
            LOGGER.warn("ME Control Center icon rendering is unavailable in this Java runtime: {}", e.toString());
            return Optional.empty();
        } catch (Exception e) {
            LOGGER.debug("Could not render icon {}: {}", iconKey, e.toString());
            return Optional.empty();
        }
    }
}
