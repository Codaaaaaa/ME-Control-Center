package io.github.codaaaaaa.mecc.runtime.resources;

import io.github.codaaaaaa.mecc.core.resources.ResourceIndex;
import io.github.codaaaaaa.mecc.platform.StoragePlatform;
import io.github.codaaaaaa.mecc.platform.thread.ServerThreadGateway;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Per-network cache of immutable storage snapshots (spec section 33).
 *
 * <ul>
 *   <li>A snapshot younger than {@code maxAge} is reused, so server-thread work is bounded by time, not by
 *       the number of browsers polling.</li>
 *   <li>Concurrent requests share one in-flight capture.</li>
 *   <li>The last few snapshots stay addressable by ID so a client can page through one consistent view.</li>
 *   <li>The server thread only copies references; conversion runs on an ME Control Center worker.</li>
 * </ul>
 */
public final class ResourceSnapshots {
    static final int RETAINED = 4;
    static final Duration CAPTURE_TIMEOUT = Duration.ofSeconds(5);
    static final Duration IDLE_EVICTION = Duration.ofMinutes(5);

    private final StoragePlatform storage;
    private final ServerThreadGateway gateway;
    private final Executor workers;
    private final Clock clock;
    private final Duration maxAge;
    private final AtomicLong sequence = new AtomicLong();
    private final Map<UUID, NetworkSnapshots> networks = new ConcurrentHashMap<>();

    public ResourceSnapshots(StoragePlatform storage, ServerThreadGateway gateway, Executor workers, Clock clock, Duration maxAge) {
        this.storage = storage;
        this.gateway = gateway;
        this.workers = workers;
        this.clock = clock;
        this.maxAge = maxAge;
    }

    /** One immutable snapshot plus lazily built per-locale catalogs. */
    public static final class Snapshot {
        private final String id;
        private final String gridKey;
        private final ResourceIndex index;
        private final Map<String, ResourceCatalog> catalogs = new HashMap<>();

        Snapshot(String id, String gridKey, ResourceIndex index) {
            this.id = id;
            this.gridKey = gridKey;
            this.index = index;
        }

        public String id() {
            return id;
        }

        public ResourceIndex index() {
            return index;
        }

        /** Returns the catalog for a locale and tag version, building it on first use. */
        public synchronized ResourceCatalog catalog(String locale, int tagsVersion, Supplier<ResourceCatalog> builder) {
            ResourceCatalog existing = catalogs.get(locale);
            if (existing != null && existing.tagsVersion() == tagsVersion) {
                return existing;
            }
            ResourceCatalog built = builder.get();
            catalogs.put(locale, built);
            return built;
        }
    }

    private static final class NetworkSnapshots {
        private final Deque<Snapshot> recent = new ArrayDeque<>();
        private CompletableFuture<Snapshot> inFlight;
        private Instant lastAccess;
    }

    /** The newest snapshot of the grid, capturing a new one when the cached one is too old. */
    public CompletableFuture<Snapshot> latest(UUID networkId, String gridKey) {
        NetworkSnapshots state = networks.computeIfAbsent(networkId, id -> new NetworkSnapshots());
        synchronized (state) {
            Instant now = clock.instant();
            state.lastAccess = now;
            Snapshot newest = state.recent.peekFirst();
            if (newest != null && newest.gridKey.equals(gridKey)
                    && Duration.between(newest.index.capturedAt(), now).compareTo(maxAge) < 0) {
                return CompletableFuture.completedFuture(newest);
            }
            if (state.inFlight != null) {
                return state.inFlight;
            }
            CompletableFuture<Snapshot> capture = gateway.call("storage.capture", () -> storage.capture(gridKey), CAPTURE_TIMEOUT)
                    .thenApplyAsync(storage::describe, workers)
                    .thenApply(index -> new Snapshot(Long.toString(sequence.incrementAndGet(), 36), gridKey, index));
            state.inFlight = capture;
            capture.whenComplete((snapshot, error) -> {
                synchronized (state) {
                    state.inFlight = null;
                    if (snapshot != null) {
                        state.recent.addFirst(snapshot);
                        while (state.recent.size() > RETAINED) {
                            state.recent.removeLast();
                        }
                    }
                }
            });
            return capture;
        }
    }

    /** A retained snapshot by ID, if it still exists. */
    public Optional<Snapshot> find(UUID networkId, String snapshotId) {
        NetworkSnapshots state = networks.get(networkId);
        if (state == null || snapshotId == null) {
            return Optional.empty();
        }
        synchronized (state) {
            state.lastAccess = clock.instant();
            return state.recent.stream().filter(snapshot -> snapshot.id.equals(snapshotId)).findFirst();
        }
    }

    /** Drops snapshots of networks nobody has looked at recently. */
    public void evictIdle() {
        Instant cutoff = clock.instant().minus(IDLE_EVICTION);
        networks.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                // lastAccess is still null for an entry created a moment ago whose request has not run yet.
                Instant lastAccess = entry.getValue().lastAccess;
                return entry.getValue().inFlight == null && lastAccess != null && lastAccess.isBefore(cutoff);
            }
        });
    }
}
