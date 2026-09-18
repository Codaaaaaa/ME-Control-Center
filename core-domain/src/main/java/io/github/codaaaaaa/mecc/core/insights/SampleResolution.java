package io.github.codaaaaaa.mecc.core.insights;

import java.time.Duration;

/**
 * Time-series tables (spec section 22), finest first. {@link #RAW} holds one row per sample; the others hold
 * aggregates (first, last, min, max, sum, count) per fixed bucket.
 */
public enum SampleResolution {
    /** Bucket width is the sampling interval, which is configurable. */
    RAW(null),
    ONE_MINUTE(Duration.ofMinutes(1)),
    FIVE_MINUTES(Duration.ofMinutes(5)),
    ONE_HOUR(Duration.ofHours(1));

    private final Duration bucket;

    SampleResolution(Duration bucket) {
        this.bucket = bucket;
    }

    /** Aggregate bucket width, or {@code null} for {@link #RAW}. */
    public Duration bucket() {
        return bucket;
    }
}
