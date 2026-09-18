package io.github.codaaaaaa.mecc.core.insights;

import io.github.codaaaaaa.mecc.core.resources.ResourceId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Shared time series keyed by {@code (networkId, resourceId)} (spec sections 21-22). */
public interface SampleRepository {

    /**
     * Stores one sample per resource and folds it into every aggregate table. A sample at an instant that is
     * already stored is ignored, so re-recording the same snapshot never counts twice.
     */
    void record(UUID networkId, Instant at, Map<ResourceId, Long> amounts);

    /**
     * Points of one series in {@code [from, to)}, grouped into buckets of {@code stepMillis} (a multiple of the
     * table's own step), oldest first. Buckets without samples are absent.
     */
    List<SeriesPoint> series(UUID networkId, ResourceId resource, SampleResolution source, Instant from, Instant to,
                             long stepMillis);

    /** Time of the oldest retained sample of a series in any table. */
    Optional<Instant> oldest(UUID networkId, ResourceId resource);

    /** Deletes rows of one table older than {@code before}. Returns the number deleted. */
    int purge(SampleResolution resolution, Instant before);

    /** @param start bucket start */
    record SeriesPoint(Instant start, double avg, long min, long max) {
    }
}
