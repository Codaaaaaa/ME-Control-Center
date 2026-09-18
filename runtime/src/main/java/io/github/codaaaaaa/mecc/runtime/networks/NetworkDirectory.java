package io.github.codaaaaaa.mecc.runtime.networks;

import io.github.codaaaaaa.mecc.core.networks.BlockLocation;
import io.github.codaaaaaa.mecc.core.networks.DiscoverySnapshot;
import io.github.codaaaaaa.mecc.core.networks.NetworkAnchor;
import io.github.codaaaaaa.mecc.core.networks.NetworkReconciler;
import io.github.codaaaaaa.mecc.core.networks.NetworkReconciler.AnchorAddition;
import io.github.codaaaaaa.mecc.core.networks.NetworkReconciler.AnchorRemoval;
import io.github.codaaaaaa.mecc.core.networks.NetworkReconciler.Resolution;
import io.github.codaaaaaa.mecc.core.networks.NetworkRecordStatus;
import io.github.codaaaaaa.mecc.core.networks.WebNetwork;
import io.github.codaaaaaa.mecc.core.persistence.DataStore;
import io.github.codaaaaaa.mecc.core.persistence.Repositories;
import io.github.codaaaaaa.mecc.platform.NetworkPlatform;
import io.github.codaaaaaa.mecc.platform.thread.ServerThreadGateway;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps an in-memory, immutable picture of which ME Control Center networks are loaded and what state they are in.
 *
 * <p>Each refresh: load records from the database → one discovery pass on the server thread (cheap,
 * proportional to anchors) → reconcile off-thread → persist only what changed. All refreshes and
 * enrollment mutations run strictly one after another through {@link #serialized}, so reconciliation
 * never races an enrollment or deletion.
 */
public final class NetworkDirectory {
    private static final Logger LOGGER = LoggerFactory.getLogger(NetworkDirectory.class);
    static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(5);
    /** {@code last_seen_at} of online networks is persisted at most this often. */
    static final Duration LAST_SEEN_WRITE_INTERVAL = Duration.ofMinutes(1);

    private final DataStore store;
    private final ServerThreadGateway gateway;
    private final NetworkPlatform platform;
    private final Clock clock;
    private volatile State state;
    private CompletableFuture<?> tail = CompletableFuture.completedFuture(null);
    private volatile boolean stopped;
    private final List<Consumer<State>> listeners = new CopyOnWriteArrayList<>();

    /**
     * An immutable directory picture.
     *
     * @param snapshot    the discovery pass it is based on
     * @param records     network records as of that pass (after persisting changes)
     * @param result      reconciliation of {@code records} against {@code snapshot}
     */
    public record State(DiscoverySnapshot snapshot, Map<UUID, WebNetwork> records, NetworkReconciler.Result result) {
    }

    public NetworkDirectory(DataStore store, ServerThreadGateway gateway, NetworkPlatform platform, Clock clock) {
        this.store = store;
        this.gateway = gateway;
        this.platform = platform;
        this.clock = clock;
    }

    /** The latest picture, or {@code null} before the first successful discovery. */
    public State current() {
        return state;
    }

    /** Called on an ME Control Center worker thread after every new picture. Listeners must not block. */
    public void addListener(Consumer<State> listener) {
        listeners.add(listener);
    }

    /** Starts periodic refreshes with a fixed delay between the end of one and the start of the next. */
    public void start(ScheduledExecutorService scheduler, Duration interval) {
        scheduler.execute(() -> runPeriodic(scheduler, interval));
    }

    public void stop() {
        stopped = true;
    }

    private void runPeriodic(ScheduledExecutorService scheduler, Duration interval) {
        if (stopped) {
            return;
        }
        refresh().whenComplete((ignored, error) -> {
            if (error != null) {
                LOGGER.debug("ME Control Center network discovery skipped: {}", error.toString());
            }
            if (!stopped && !scheduler.isShutdown()) {
                try {
                    scheduler.schedule(() -> runPeriodic(scheduler, interval), interval.toMillis(), TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    // Scheduler shut down concurrently: the runtime is stopping.
                }
            }
        });
    }

    /** Runs a full refresh after any in-flight directory work. */
    public CompletableFuture<State> refresh() {
        return serialized(this::refreshNow);
    }

    /**
     * Runs a mutation of network records with exclusive access to the directory: first a fresh discovery
     * (so the mutation sees current world state), then {@code mutation}, then re-reconciliation of the
     * same snapshot against the updated records.
     */
    public <T> CompletableFuture<T> mutate(Function<State, CompletableFuture<T>> mutation) {
        return serialized(() -> refreshNow()
                .thenCompose(fresh -> mutation.apply(fresh)
                        .thenCompose(value -> recompute(fresh.snapshot()).thenApply(ignored -> value))));
    }

    /** Re-reconciles the latest snapshot against current records without a new discovery pass. */
    public CompletableFuture<State> recomputeLatest() {
        return serialized(() -> {
            State current = state;
            return current == null ? CompletableFuture.completedFuture(null) : recompute(current.snapshot());
        });
    }

    private synchronized <T> CompletableFuture<T> serialized(java.util.function.Supplier<CompletableFuture<T>> work) {
        CompletableFuture<T> result = tail.handle((ignored, error) -> null).thenCompose(ignored -> work.get());
        tail = result;
        return result;
    }

    private CompletableFuture<State> refreshNow() {
        return store.read(repos -> repos.networks().listAll())
                .thenCompose(records -> {
                    List<BlockLocation> anchors = records.stream()
                            .flatMap(record -> record.anchors().stream().map(NetworkAnchor::location))
                            .toList();
                    return gateway.call("networks.discover", () -> platform.discover(anchors), DISCOVERY_TIMEOUT)
                            .thenCompose(snapshot -> persist(records, snapshot));
                });
    }

    private CompletableFuture<State> recompute(DiscoverySnapshot snapshot) {
        return store.read(repos -> repos.networks().listAll()).thenCompose(records -> persist(records, snapshot));
    }

    private CompletableFuture<State> persist(List<WebNetwork> records, DiscoverySnapshot snapshot) {
        NetworkReconciler.Result result = NetworkReconciler.reconcile(records, snapshot);
        Instant now = clock.instant();

        boolean dirty = !result.anchorsToRemove().isEmpty() || !result.anchorsToAdd().isEmpty()
                || records.stream().anyMatch(record ->
                        needsObservationWrite(record, result.resolutions().get(record.id()), now));

        CompletableFuture<List<WebNetwork>> stored = !dirty
                ? CompletableFuture.completedFuture(records)
                : store.write(repos -> {
                    apply(repos, records, result, now);
                    return repos.networks().listAll();
                });

        return stored.thenApply(updated -> {
            // Anchor changes alter records, not resolutions: reconcile once more so views see stored anchors.
            NetworkReconciler.Result finalResult = updated == records ? result : NetworkReconciler.reconcile(updated, snapshot);
            Map<UUID, WebNetwork> byId = new HashMap<>();
            updated.forEach(record -> byId.put(record.id(), record));
            State next = new State(snapshot, Map.copyOf(byId), finalResult);
            state = next;
            for (Consumer<State> listener : listeners) {
                try {
                    listener.accept(next);
                } catch (RuntimeException e) {
                    LOGGER.warn("ME Control Center network directory listener failed", e);
                }
            }
            return next;
        });
    }

    private void apply(Repositories repos, List<WebNetwork> records, NetworkReconciler.Result result, Instant now) {
        for (AnchorRemoval removal : result.anchorsToRemove()) {
            LOGGER.info("ME Control Center network {}: anchor {} no longer exists and was detached", removal.networkId(), removal.location());
            repos.networks().removeAnchor(removal.networkId(), removal.location());
        }
        for (AnchorAddition addition : result.anchorsToAdd()) {
            NetworkAnchor anchor = new NetworkAnchor(addition.anchor().location(), addition.anchor().ownerUuid(), now);
            if (repos.networks().addAnchor(addition.networkId(), anchor)) {
                LOGGER.info("ME Control Center network {}: attached new anchor {}", addition.networkId(), anchor.location());
            }
        }
        for (WebNetwork record : records) {
            Resolution resolution = result.resolutions().get(record.id());
            if (needsObservationWrite(record, resolution, now)) {
                if (resolution.status() == NetworkRecordStatus.CONFLICT && record.status() != NetworkRecordStatus.CONFLICT) {
                    LOGGER.warn("ME Control Center network {} ({}) has an ambiguous identity ({}); live access is paused until it resolves",
                            record.displayName(), record.id(), resolution.reason());
                }
                Instant lastSeen = resolution.status() == NetworkRecordStatus.ONLINE ? now : null;
                repos.networks().updateObservation(record.id(), resolution.status(), lastSeen);
            }
        }
    }

    private static boolean needsObservationWrite(WebNetwork record, Resolution resolution, Instant now) {
        if (resolution == null) {
            return false;
        }
        if (record.status() != resolution.status()) {
            return true;
        }
        return resolution.status() == NetworkRecordStatus.ONLINE
                && (record.lastSeenAt() == null
                || Duration.between(record.lastSeenAt(), now).compareTo(LAST_SEEN_WRITE_INTERVAL) >= 0);
    }
}
