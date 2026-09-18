package io.github.codaaaaaa.mecc.platform;

import io.github.codaaaaaa.mecc.core.resources.ResourceIndex;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Reads ME network storage (spec section 43, {@code StoragePlatform}).
 *
 * <p>Reading is split so the server thread does as little as possible: {@link #capture} only copies
 * references and amounts out of AE2's already-maintained cache; {@link #describe} does the expensive
 * conversion (identifiers, names, units) on an ME Control Center worker thread.
 */
public interface StoragePlatform {

    /**
     * Copies the current storage contents, craftable resources, and active crafting requests of a grid.
     *
     * @param gridKey runtime key of a grid from the latest discovery snapshot
     * @throws io.github.codaaaaaa.mecc.core.error.MeccException {@code NETWORK_OFFLINE} if the grid no longer exists
     */
    @ServerThreadOnly
    StorageCapture capture(String gridKey);

    /**
     * Converts a capture into an immutable, platform-independent index. Must <em>not</em> be called on the
     * server thread; reads only immutable resource keys and frozen registries.
     */
    ResourceIndex describe(StorageCapture capture);

    /**
     * Tags per resource, keyed by resource id text without variant (e.g. {@code item:minecraft:iron_ingot}),
     * values like {@code forge:ingots/iron}.
     */
    @ServerThreadOnly
    Map<String, List<String>> captureTags();

    /** Increments whenever tags are reloaded, so cached tag maps can be refreshed. Thread-safe. */
    int tagsVersion();

    /** Opaque result of {@link #capture}. Holds references only; safe to pass to another thread. */
    interface StorageCapture {
        Instant capturedAt();

        int size();
    }
}
