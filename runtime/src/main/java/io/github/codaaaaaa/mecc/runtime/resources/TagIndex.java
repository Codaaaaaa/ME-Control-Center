package io.github.codaaaaaa.mecc.runtime.resources;

import io.github.codaaaaaa.mecc.platform.StoragePlatform;
import io.github.codaaaaaa.mecc.platform.thread.ServerThreadGateway;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resource tags for {@code #tag} search and the detail panel. Captured once on the server thread and
 * again only after a tag reload.
 */
public final class TagIndex {
    private static final Logger LOGGER = LoggerFactory.getLogger(TagIndex.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    public record Tags(Map<String, List<String>> byResource, int version) {
    }

    private final StoragePlatform storage;
    private final ServerThreadGateway gateway;
    private volatile Tags current = new Tags(Map.of(), -1);
    private CompletableFuture<Tags> refreshing;

    public TagIndex(StoragePlatform storage, ServerThreadGateway gateway) {
        this.storage = storage;
        this.gateway = gateway;
    }

    /** Current tags, refreshed first if tags were reloaded. Never fails: falls back to the previous tags. */
    public synchronized CompletableFuture<Tags> tags() {
        int version = storage.tagsVersion();
        Tags known = current;
        if (known.version() == version) {
            return CompletableFuture.completedFuture(known);
        }
        if (refreshing != null) {
            return refreshing;
        }
        CompletableFuture<Tags> refresh = gateway.call("storage.tags", storage::captureTags, TIMEOUT)
                .handle((tags, error) -> {
                    if (error != null) {
                        LOGGER.debug("Could not capture resource tags: {}", error.toString());
                        return known;
                    }
                    return new Tags(Map.copyOf(tags), version);
                });
        refreshing = refresh;
        refresh.whenComplete((tags, error) -> {
            synchronized (this) {
                refreshing = null;
                if (tags != null) {
                    current = tags;
                }
            }
        });
        return refresh;
    }
}
