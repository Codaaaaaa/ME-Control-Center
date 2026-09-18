package io.github.codaaaaaa.mecc.persistence.sqlite;

import io.github.codaaaaaa.mecc.core.insights.SampleRepository;
import io.github.codaaaaaa.mecc.core.insights.SampleResolution;
import io.github.codaaaaaa.mecc.core.resources.ResourceId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Time series in SQLite. Aggregates are maintained incrementally: every new raw sample is folded into its
 * 1-minute, 5-minute, and 1-hour bucket in the same transaction, so there is no roll-up job to fall behind
 * and nothing to recompute after a restart.
 */
final class SqliteSampleRepository implements SampleRepository {
    private static final List<SampleResolution> AGGREGATES =
            List.of(SampleResolution.ONE_MINUTE, SampleResolution.FIVE_MINUTES, SampleResolution.ONE_HOUR);

    private final Jdbc jdbc;

    SqliteSampleRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    static String table(SampleResolution resolution) {
        return switch (resolution) {
            case RAW -> "resource_samples_15s";
            case ONE_MINUTE -> "resource_samples_1m";
            case FIVE_MINUTES -> "resource_samples_5m";
            case ONE_HOUR -> "resource_samples_1h";
        };
    }

    @Override
    public void record(UUID networkId, Instant at, Map<ResourceId, Long> amounts) {
        long millis = at.toEpochMilli();
        for (Map.Entry<ResourceId, Long> sample : amounts.entrySet()) {
            String resource = sample.getKey().toString();
            long amount = sample.getValue();
            int inserted = jdbc.update("INSERT OR IGNORE INTO resource_samples_15s (network_id, resource_id, at, amount) "
                    + "VALUES (?, ?, ?, ?)", networkId, resource, millis, amount);
            if (inserted == 0) {
                continue;
            }
            for (SampleResolution resolution : AGGREGATES) {
                long width = resolution.bucket().toMillis();
                jdbc.update("INSERT INTO " + table(resolution) + " (network_id, resource_id, bucket, first_amount, "
                                + "last_amount, min_amount, max_amount, sum_amount, sample_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1) "
                                + "ON CONFLICT (network_id, resource_id, bucket) DO UPDATE SET "
                                + "last_amount = excluded.last_amount, "
                                + "min_amount = MIN(min_amount, excluded.min_amount), "
                                + "max_amount = MAX(max_amount, excluded.max_amount), "
                                + "sum_amount = sum_amount + excluded.sum_amount, "
                                + "sample_count = sample_count + 1",
                        networkId, resource, Math.floorDiv(millis, width) * width, amount, amount, amount, amount,
                        (double) amount);
            }
        }
    }

    @Override
    public List<SeriesPoint> series(UUID networkId, ResourceId resource, SampleResolution source, Instant from, Instant to,
                                    long stepMillis) {
        String sql = source == SampleResolution.RAW
                ? "SELECT (at / ?) * ? AS start, AVG(amount) AS avg, MIN(amount) AS min, MAX(amount) AS max "
                        + "FROM resource_samples_15s WHERE network_id = ? AND resource_id = ? AND at >= ? AND at < ? "
                        + "GROUP BY start ORDER BY start"
                : "SELECT (bucket / ?) * ? AS start, SUM(sum_amount) / SUM(sample_count) AS avg, MIN(min_amount) AS min, "
                        + "MAX(max_amount) AS max FROM " + table(source)
                        + " WHERE network_id = ? AND resource_id = ? AND bucket >= ? AND bucket < ? GROUP BY start ORDER BY start";
        return jdbc.query(sql, row -> new SeriesPoint(Instant.ofEpochMilli(row.getLong("start")), row.getDouble("avg"),
                        row.getLong("min"), row.getLong("max")),
                stepMillis, stepMillis, networkId, resource.toString(), from.toEpochMilli(), to.toEpochMilli());
    }

    @Override
    public Optional<Instant> oldest(UUID networkId, ResourceId resource) {
        Long oldest = null;
        for (SampleResolution resolution : SampleResolution.values()) {
            String column = resolution == SampleResolution.RAW ? "at" : "bucket";
            Optional<Long> value = jdbc.queryOne("SELECT MIN(" + column + ") FROM " + table(resolution)
                    + " WHERE network_id = ? AND resource_id = ?", row -> {
                        long min = row.getLong(1);
                        return row.wasNull() ? null : min;
                    }, networkId, resource.toString());
            if (value.isPresent() && (oldest == null || value.get() < oldest)) {
                oldest = value.get();
            }
        }
        return Optional.ofNullable(oldest).map(Instant::ofEpochMilli);
    }

    @Override
    public int purge(SampleResolution resolution, Instant before) {
        String column = resolution == SampleResolution.RAW ? "at" : "bucket";
        return jdbc.update("DELETE FROM " + table(resolution) + " WHERE " + column + " < ?", before.toEpochMilli());
    }
}
