package io.github.codaaaaaa.mecc.core.config;

import io.github.codaaaaaa.mecc.core.insights.SampleResolution;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Watchlist sampling and time-series retention ({@code [analytics]} section, spec sections 21-22 and 46).
 *
 * @param enabled                    whether watched resources are sampled at all
 * @param sampleIntervalSeconds      one storage snapshot per watched network per interval
 * @param maxWatchlistEntriesPerUser watchlist entries one player may keep across all networks
 */
public record AnalyticsConfig(
        boolean enabled,
        int sampleIntervalSeconds,
        int rawRetentionHours,
        int oneMinuteRetentionDays,
        int fiveMinuteRetentionDays,
        int oneHourRetentionDays,
        int maxWatchlistEntriesPerUser) {

    public static AnalyticsConfig defaults() {
        return new AnalyticsConfig(true, 15, 24, 7, 90, 730, 100);
    }

    /** How long rows of a table are kept. */
    public Duration retention(SampleResolution resolution) {
        return switch (resolution) {
            case RAW -> Duration.ofHours(rawRetentionHours);
            case ONE_MINUTE -> Duration.ofDays(oneMinuteRetentionDays);
            case FIVE_MINUTES -> Duration.ofDays(fiveMinuteRetentionDays);
            case ONE_HOUR -> Duration.ofDays(oneHourRetentionDays);
        };
    }

    /** Width of one row of a table: the sampling interval for raw samples. */
    public Duration step(SampleResolution resolution) {
        return resolution == SampleResolution.RAW ? Duration.ofSeconds(sampleIntervalSeconds) : resolution.bucket();
    }

    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        check(problems, "sample_interval_seconds", sampleIntervalSeconds, 5, 300);
        check(problems, "raw_retention_hours", rawRetentionHours, 1, 168);
        check(problems, "one_minute_retention_days", oneMinuteRetentionDays, 1, 90);
        check(problems, "five_minute_retention_days", fiveMinuteRetentionDays, 1, 730);
        check(problems, "one_hour_retention_days", oneHourRetentionDays, 1, 3650);
        check(problems, "max_watchlist_entries_per_user", maxWatchlistEntriesPerUser, 1, 1000);
        return problems;
    }

    private static void check(List<String> problems, String key, int value, int min, int max) {
        if (value < min || value > max) {
            problems.add("analytics." + key + " must be between " + min + " and " + max + ", got " + value);
        }
    }
}
