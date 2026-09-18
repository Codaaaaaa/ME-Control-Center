package io.github.codaaaaaa.mecc.core.insights;

import io.github.codaaaaaa.mecc.core.resources.ResourceViews.ResourceLabel;
import java.time.Instant;
import java.util.List;

/** API response models for the watchlist and charts. */
public final class InsightsViews {
    private InsightsViews() {
    }

    /**
     * @param amount    current stock, or {@code null} when the network cannot be read right now
     * @param craftable {@code null} when unknown
     * @param crafting  amount requested by running jobs, or {@code null}
     */
    public record WatchEntryView(String id, String networkId, ResourceLabel resource, Instant createdAt, Long amount,
                                 Boolean craftable, Long crafting) {
    }

    /** @param capturedAt time of the storage snapshot behind the amounts, or {@code null} when offline */
    public record Watchlist(List<WatchEntryView> entries, Instant capturedAt, int limit, String assetVersion) {
    }

    /** @param points {@code [epochMillis, avg, min, max]}; gaps have {@code null} values */
    public record SeriesView(String entryId, String resourceId, List<Object[]> points) {
    }

    /**
     * @param resolution table the points were read from
     * @param sampling   whether the server samples watched resources at all
     */
    public record SeriesSet(String range, Instant from, Instant to, long stepSeconds, SampleResolution resolution,
                            boolean sampling, List<SeriesView> series) {
    }
}
