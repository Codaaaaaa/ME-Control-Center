package io.github.codaaaaaa.mecc.core.insights;

import java.time.Duration;
import java.util.Optional;

/** Chart ranges offered by the UI (spec section 6.3). */
public enum InsightsRange {
    H1("1h", Duration.ofHours(1)),
    H6("6h", Duration.ofHours(6)),
    D1("1d", Duration.ofDays(1)),
    D7("7d", Duration.ofDays(7)),
    D30("30d", Duration.ofDays(30)),
    D180("180d", Duration.ofDays(180)),
    D360("360d", Duration.ofDays(360)),
    /** From the oldest retained sample. */
    MAX("max", null);

    private final String key;
    private final Duration length;

    InsightsRange(String key, Duration length) {
        this.key = key;
        this.length = length;
    }

    public String key() {
        return key;
    }

    /** Fixed length, or {@code null} for {@link #MAX}. */
    public Duration length() {
        return length;
    }

    public static Optional<InsightsRange> parse(String key) {
        for (InsightsRange range : values()) {
            if (range.key.equalsIgnoreCase(key)) {
                return Optional.of(range);
            }
        }
        return Optional.empty();
    }
}
