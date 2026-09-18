package io.github.codaaaaaa.mecc.runtime.patterns;

import io.github.codaaaaaa.mecc.platform.PatternPlatform;
import io.github.codaaaaaa.mecc.platform.PatternPlatform.ProviderCapture;
import io.github.codaaaaaa.mecc.platform.thread.ServerThreadGateway;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-network cache of pattern provider captures: however many browsers look at a network, its providers are
 * read on the server thread at most once per {@code maxAge}, and concurrent readers share one capture.
 */
public final class ProviderSnapshots {
    static final Duration CAPTURE_TIMEOUT = Duration.ofSeconds(5);

    private final PatternPlatform patterns;
    private final ServerThreadGateway gateway;
    private final Clock clock;
    private final Duration maxAge;
    private final Map<UUID, Entry> networks = new ConcurrentHashMap<>();

    private static final class Entry {
        private String gridKey;
        private ProviderCapture latest;
        private CompletableFuture<ProviderCapture> inFlight;
    }

    public ProviderSnapshots(PatternPlatform patterns, ServerThreadGateway gateway, Clock clock, Duration maxAge) {
        this.patterns = patterns;
        this.gateway = gateway;
        this.clock = clock;
        this.maxAge = maxAge;
    }

    /** A capture no older than {@code maxAge}, reading the providers again if needed. */
    public CompletableFuture<ProviderCapture> latest(UUID networkId, String gridKey) {
        Entry entry = networks.computeIfAbsent(networkId, id -> new Entry());
        synchronized (entry) {
            ProviderCapture latest = entry.latest;
            if (latest != null && gridKey.equals(entry.gridKey)
                    && Duration.between(latest.capturedAt(), clock.instant()).compareTo(maxAge) < 0) {
                return CompletableFuture.completedFuture(latest);
            }
            if (entry.inFlight != null && gridKey.equals(entry.gridKey)) {
                return entry.inFlight;
            }
            CompletableFuture<ProviderCapture> capture = gateway.call("patterns.providers",
                    () -> patterns.captureProviders(gridKey), CAPTURE_TIMEOUT);
            entry.gridKey = gridKey;
            entry.latest = null;
            entry.inFlight = capture;
            capture.whenComplete((result, error) -> {
                synchronized (entry) {
                    if (entry.inFlight == capture) {
                        entry.inFlight = null;
                        if (result != null) {
                            entry.latest = result;
                        }
                    }
                }
            });
            return capture;
        }
    }

    /** Drops the cached capture, e.g. after a pattern was deployed, so the next read shows it. */
    public void invalidate(UUID networkId) {
        Entry entry = networks.get(networkId);
        if (entry != null) {
            synchronized (entry) {
                entry.latest = null;
                entry.inFlight = null;
            }
        }
    }
}
