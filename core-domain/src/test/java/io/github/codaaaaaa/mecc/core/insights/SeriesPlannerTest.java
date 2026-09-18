package io.github.codaaaaaa.mecc.core.insights;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.codaaaaaa.mecc.core.config.AnalyticsConfig;
import io.github.codaaaaaa.mecc.core.insights.SampleRepository.SeriesPoint;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SeriesPlannerTest {
    private static final AnalyticsConfig DEFAULTS = AnalyticsConfig.defaults();

    private static void assertPlan(InsightsRange range, SampleResolution source, Duration step) {
        SeriesPlanner.Plan plan = SeriesPlanner.plan(range.length(), DEFAULTS);
        assertEquals(source, plan.source(), range.key());
        assertEquals(step.toMillis(), plan.stepMillis(), range.key());
    }

    @Test
    void picksTheCoarsestRetainedTableThatStillGivesEnoughPoints() {
        assertPlan(InsightsRange.H1, SampleResolution.RAW, Duration.ofSeconds(15));
        assertPlan(InsightsRange.H6, SampleResolution.ONE_MINUTE, Duration.ofMinutes(1));
        assertPlan(InsightsRange.D1, SampleResolution.ONE_MINUTE, Duration.ofMinutes(4));
        assertPlan(InsightsRange.D7, SampleResolution.FIVE_MINUTES, Duration.ofMinutes(30));
        assertPlan(InsightsRange.D30, SampleResolution.ONE_HOUR, Duration.ofHours(2));
        assertPlan(InsightsRange.D360, SampleResolution.ONE_HOUR, Duration.ofHours(24));
    }

    @Test
    void neverReadsATableThatNoLongerHoldsTheRange() {
        // 7 days of 1-minute data would be fine-grained enough, but only 2 days are kept.
        AnalyticsConfig shortMinutes = new AnalyticsConfig(true, 15, 24, 2, 90, 730, 100);
        assertEquals(SampleResolution.FIVE_MINUTES, SeriesPlanner.plan(Duration.ofDays(3), shortMinutes).source());
        // Longer than every retention: the hourly table is the best there is.
        assertEquals(SampleResolution.ONE_HOUR, SeriesPlanner.plan(Duration.ofDays(5000), DEFAULTS).source());
    }

    @Test
    void marksGapsInsteadOfBridgingThem() {
        long step = 15_000;
        Instant start = Instant.ofEpochMilli(1_000_000_000L);
        List<Object[]> points = SeriesPlanner.withGaps(List.of(
                new SeriesPoint(start, 10, 10, 10),
                new SeriesPoint(start.plusMillis(2 * step), 11, 11, 11), // one missing bucket: jitter, not a gap
                new SeriesPoint(start.plusMillis(10 * step), 12, 12, 12)), step);
        assertEquals(4, points.size());
        assertArrayEquals(new Object[] {start.toEpochMilli() + 3 * step, null, null, null}, points.get(2));
        assertEquals(12.0, points.get(3)[1]);
    }
}
