package io.github.codaaaaaa.mecc.runtime.crafting;

import io.github.codaaaaaa.mecc.platform.CraftingPlatform;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform.CpuCapture;
import io.github.codaaaaaa.mecc.platform.thread.ServerThreadGateway;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Per-network cache of crafting CPU captures. However many browsers and tracked orders look at a network,
 * its CPUs are read on the server thread at most once per {@code maxAge}, and concurrent readers share one
 * in-flight capture. Every capture is also handed to the order tracker, so job outcomes are processed no
 * matter who triggered the read.
 */
public final class CpuSnapshots {
    static final Duration CAPTURE_TIMEOUT = Duration.ofSeconds(5);

    private final CraftingPlatform crafting;
    private final ServerThreadGateway gateway;
    private final Clock clock;
    private final Duration maxAge;
    private final Function<UUID, Set<String>> watchedJobs;
    private final Map<UUID, Entry> networks = new ConcurrentHashMap<>();
    private volatile BiConsumer<UUID, CpuCapture> onCapture = (network, capture) -> {
    };

    private static final class Entry {
        private String gridKey;
        private Set<String> watched = Set.of();
        private CpuCapture latest;
        private CompletableFuture<CpuCapture> inFlight;
    }

    /**
     * @param watchedJobs job IDs ME Control Center tracks on a network; their fate is reported with each capture
     */
    public CpuSnapshots(CraftingPlatform crafting, ServerThreadGateway gateway, Clock clock, Duration maxAge,
                        Function<UUID, Set<String>> watchedJobs) {
        this.crafting = crafting;
        this.gateway = gateway;
        this.clock = clock;
        this.maxAge = maxAge;
        this.watchedJobs = watchedJobs;
    }

    public void onCapture(BiConsumer<UUID, CpuCapture> listener) {
        this.onCapture = listener;
    }

    /** A capture no older than {@code maxAge}, reading the CPUs again if needed. */
    public CompletableFuture<CpuCapture> latest(UUID networkId, String gridKey) {
        Set<String> watched = Set.copyOf(watchedJobs.apply(networkId));
        Entry entry = networks.computeIfAbsent(networkId, id -> new Entry());
        synchronized (entry) {
            CpuCapture latest = entry.latest;
            if (latest != null && gridKey.equals(entry.gridKey) && entry.watched.containsAll(watched)
                    && Duration.between(latest.capturedAt(), clock.instant()).compareTo(maxAge) < 0) {
                return CompletableFuture.completedFuture(latest);
            }
            if (entry.inFlight != null && gridKey.equals(entry.gridKey) && entry.watched.containsAll(watched)) {
                return entry.inFlight;
            }
            CompletableFuture<CpuCapture> capture = gateway.call("crafting.cpus",
                    () -> crafting.captureCpus(gridKey, watched), CAPTURE_TIMEOUT);
            entry.gridKey = gridKey;
            entry.watched = watched;
            entry.inFlight = capture;
            capture.whenComplete((result, error) -> {
                synchronized (entry) {
                    if (entry.inFlight == capture) {
                        entry.inFlight = null;
                    }
                    if (result != null) {
                        entry.latest = result;
                    }
                }
            });
            return capture.thenApply(result -> {
                onCapture.accept(networkId, result);
                return result;
            });
        }
    }

    /** The newest capture of a network, if any, without reading the CPUs. */
    public CpuCapture cached(UUID networkId) {
        Entry entry = networks.get(networkId);
        if (entry == null) {
            return null;
        }
        synchronized (entry) {
            return entry.latest;
        }
    }

    public void forget(UUID networkId) {
        networks.remove(networkId);
    }
}
