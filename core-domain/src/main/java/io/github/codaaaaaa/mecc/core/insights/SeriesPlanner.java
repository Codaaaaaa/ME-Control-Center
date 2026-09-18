package io.github.codaaaaaa.mecc.core.insights;

import io.github.codaaaaaa.mecc.core.config.AnalyticsConfig;
import io.github.codaaaaaa.mecc.core.insights.SampleRepository.SeriesPoint;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Chooses how to read a chart range (spec section 22) and marks gaps in what was read (section 21.5). */
public final class SeriesPlanner {
    /** Points per series a chart aims for; enough for detail, small enough for phones. */
    static final int TARGET_POINTS = 360;

    private SeriesPlanner() {
    }

    /** Read {@code source}, grouped into buckets of {@code stepMillis}. */
    public record Plan(SampleResolution source, long stepMillis) {
    }

    /**
     * The coarsest table whose rows are still at most {@code range / TARGET_POINTS} wide, among the tables that
     * retain the whole range; the finest retaining table when all are coarser; the hourly table when none
     * retains the range. The step is the table's own width rounded up to reach the point target.
     */
    public static Plan plan(Duration range, AnalyticsConfig config) {
        long target = Math.max(1, range.toMillis() / TARGET_POINTS);
        SampleResolution chosen = null;
        for (SampleResolution resolution : SampleResolution.values()) {
            if (config.retention(resolution).compareTo(range) < 0) {
                continue;
            }
            if (chosen == null || config.step(resolution).toMillis() <= target) {
                chosen = resolution;
            }
        }
        if (chosen == null) {
            chosen = SampleResolution.ONE_HOUR;
        }
        long base = config.step(chosen).toMillis();
        long multiple = (target + base - 1) / base;
        return new Plan(chosen, Math.max(1, multiple) * base);
    }

    /**
     * Points as {@code [epochMillis, avg, min, max]}, with a {@code [epochMillis, null, null, null]} gap marker
     * wherever more than two steps pass without data, so charts break the line instead of bridging it.
     */
    public static List<Object[]> withGaps(List<SeriesPoint> points, long stepMillis) {
        List<Object[]> result = new ArrayList<>(points.size() + 8);
        long previous = Long.MIN_VALUE;
        for (SeriesPoint point : points) {
            long at = point.start().toEpochMilli();
            if (previous != Long.MIN_VALUE && at - previous > 2 * stepMillis) {
                result.add(new Object[] {previous + stepMillis, null, null, null});
            }
            result.add(new Object[] {at, point.avg(), point.min(), point.max()});
            previous = at;
        }
        return result;
    }
}
