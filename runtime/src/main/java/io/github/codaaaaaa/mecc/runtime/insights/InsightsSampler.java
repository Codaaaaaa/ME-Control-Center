package io.github.codaaaaaa.mecc.runtime.insights;

import io.github.codaaaaaa.mecc.core.config.AnalyticsConfig;
import io.github.codaaaaaa.mecc.core.insights.SampleResolution;
import io.github.codaaaaaa.mecc.core.persistence.DataStore;
import io.github.codaaaaaa.mecc.core.resources.ResourceId;
import io.github.codaaaaaa.mecc.core.resources.ResourceIndex;
import io.github.codaaaaaa.mecc.runtime.networks.NetworkGuard;
import io.github.codaaaaaa.mecc.runtime.resources.ResourceSnapshots;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Samples watched resources (spec section 21.1). Per interval it takes one storage snapshot per network that has
 * watched resources - through the same snapshot cache the terminal uses - and records each distinct watched
 * resource once, however many players watch it. Networks that are not loaded record nothing, which charts show
 * as a gap.
 */
public final class InsightsSampler {
    private static final Logger LOGGER = LoggerFactory.getLogger(InsightsSampler.class);

    private final DataStore store;
    private final NetworkGuard guard;
    private final ResourceSnapshots snapshots;
    private final AnalyticsConfig config;
    private final Clock clock;
    private final AtomicBoolean sampling = new AtomicBoolean();

    public InsightsSampler(DataStore store, NetworkGuard guard, ResourceSnapshots snapshots, AnalyticsConfig config,
                           Clock clock) {
        this.store = store;
        this.guard = guard;
        this.snapshots = snapshots;
        this.config = config;
        this.clock = clock;
    }

    public void start(ScheduledExecutorService scheduler) {
        long interval = config.sampleIntervalSeconds();
        // Fixed rate keeps samples one interval apart, so consecutive samples never skip a raw bucket.
        scheduler.scheduleAtFixedRate(() -> sampleOnce().exceptionally(error -> {
            LOGGER.debug("ME Control Center could not sample watched resources: {}", error.toString());
            return 0;
        }), interval, interval, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(() -> purge().exceptionally(error -> {
            LOGGER.warn("ME Control Center could not delete expired resource history", error);
            return 0;
        }), 1, 60, TimeUnit.MINUTES);
    }

    /** Takes one sample of every watched resource. Returns the number of series recorded. */
    public CompletableFuture<Integer> sampleOnce() {
        if (!sampling.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(0); // The previous round is still running.
        }
        return store.read(repos -> repos.watchlist().watchedSeries())
                .thenCompose(this::capture)
                .thenCompose(samples -> samples.isEmpty()
                        ? CompletableFuture.completedFuture(0)
                        : store.write(repos -> {
                            int series = 0;
                            for (Sample sample : samples) {
                                repos.samples().record(sample.networkId(), sample.at(), sample.amounts());
                                series += sample.amounts().size();
                            }
                            return series;
                        }))
                .whenComplete((ignored, error) -> sampling.set(false));
    }

    private record Sample(UUID networkId, Instant at, Map<ResourceId, Long> amounts) {
    }

    private CompletableFuture<List<Sample>> capture(Map<UUID, Set<ResourceId>> watched) {
        List<CompletableFuture<Sample>> reads = new ArrayList<>();
        watched.forEach((networkId, resources) -> guard.onlineGridKey(networkId).ifPresent(gridKey ->
                reads.add(snapshots.latest(networkId, gridKey)
                        .thenApply(snapshot -> new Sample(networkId, snapshot.index().capturedAt(),
                                amounts(snapshot.index(), resources)))
                        .exceptionally(error -> {
                            LOGGER.debug("Could not sample network {}: {}", networkId, error.toString());
                            return null; // A gap, not a zero.
                        }))));
        return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            List<Sample> samples = new ArrayList<>();
            reads.forEach(read -> {
                Sample sample = read.join();
                if (sample != null) {
                    samples.add(sample);
                }
            });
            return samples;
        });
    }

    /** Amount of each watched resource; one the loaded network does not hold has 0. */
    static Map<ResourceId, Long> amounts(ResourceIndex index, Set<ResourceId> watched) {
        Map<ResourceId, Long> amounts = new HashMap<>();
        watched.forEach(resource -> amounts.put(resource, 0L));
        for (int i = 0; i < index.size(); i++) {
            ResourceId id = index.descriptor(i).id();
            if (amounts.containsKey(id)) {
                amounts.put(id, index.amount(i));
            }
        }
        return amounts;
    }

    /** Deletes history beyond each resolution's retention. */
    public CompletableFuture<Integer> purge() {
        Instant now = clock.instant();
        return store.write(repos -> {
            int deleted = 0;
            for (SampleResolution resolution : SampleResolution.values()) {
                deleted += repos.samples().purge(resolution, now.minus(config.retention(resolution)));
            }
            return deleted;
        });
    }
}
