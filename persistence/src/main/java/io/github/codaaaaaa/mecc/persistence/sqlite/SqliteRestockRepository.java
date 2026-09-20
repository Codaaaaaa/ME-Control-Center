package io.github.codaaaaaa.mecc.persistence.sqlite;

import io.github.codaaaaaa.mecc.core.automation.RestockRepository;
import io.github.codaaaaaa.mecc.core.automation.RestockRule;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class SqliteRestockRepository implements RestockRepository {
    private static final String COLUMNS = "id, network_id, created_by, " + StoredTargets.COLUMNS
            + ", minimum, restock_to, cpu_id, cooldown_minutes, enabled, last_run_at, last_order_id, failures, "
            + "paused_until, last_error, created_at";

    private final Jdbc jdbc;

    SqliteRestockRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(RestockRule rule) {
        jdbc.update("INSERT INTO restock_rules (" + COLUMNS + ") VALUES ("
                        + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                StoredTargets.params(new Object[] {rule.id(), rule.networkId(), rule.createdBy()}, rule.resource(),
                        rule.minimum(), rule.restockTo(), rule.cpuId(), rule.cooldown(), rule.enabled(), rule.lastRunAt(),
                        rule.lastOrderId(), rule.failures(), rule.pausedUntil(), rule.lastError(), rule.createdAt()));
    }

    @Override
    public boolean update(RestockRule rule) {
        return jdbc.update("UPDATE restock_rules SET minimum = ?, restock_to = ?, cpu_id = ?, cooldown_minutes = ?, "
                        + "enabled = ?, last_run_at = ?, last_order_id = ?, failures = ?, paused_until = ?, "
                        + "last_error = ? WHERE id = ?",
                rule.minimum(), rule.restockTo(), rule.cpuId(), rule.cooldown(), rule.enabled(), rule.lastRunAt(),
                rule.lastOrderId(), rule.failures(), rule.pausedUntil(), rule.lastError(), rule.id()) > 0;
    }

    @Override
    public Optional<RestockRule> find(UUID id) {
        return jdbc.queryOne("SELECT " + COLUMNS + " FROM restock_rules WHERE id = ?", SqliteRestockRepository::map, id);
    }

    @Override
    public List<RestockRule> list(UUID networkId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM restock_rules WHERE network_id = ? ORDER BY created_at",
                SqliteRestockRepository::map, networkId);
    }

    @Override
    public List<RestockRule> enabledRules() {
        return jdbc.query("SELECT " + COLUMNS + " FROM restock_rules WHERE enabled = 1 ORDER BY created_at",
                SqliteRestockRepository::map);
    }

    @Override
    public int countByNetwork(UUID networkId) {
        return jdbc.queryOne("SELECT COUNT(*) FROM restock_rules WHERE network_id = ?", row -> row.getInt(1), networkId)
                .orElse(0);
    }

    @Override
    public boolean delete(UUID id) {
        return jdbc.update("DELETE FROM restock_rules WHERE id = ?", id) > 0;
    }

    @Override
    public int disableAll(UUID networkId) {
        return networkId == null
                ? jdbc.update("UPDATE restock_rules SET enabled = 0 WHERE enabled = 1")
                : jdbc.update("UPDATE restock_rules SET enabled = 0 WHERE enabled = 1 AND network_id = ?", networkId);
    }

    private static RestockRule map(ResultSet row) throws SQLException {
        return new RestockRule(Jdbc.uuid(row, "id"), Jdbc.uuid(row, "network_id"), Jdbc.uuid(row, "created_by"),
                StoredTargets.read(row), row.getLong("minimum"), row.getLong("restock_to"), row.getString("cpu_id"),
                row.getInt("cooldown_minutes"), row.getInt("enabled") != 0, Jdbc.instant(row, "last_run_at"),
                Jdbc.uuid(row, "last_order_id"), row.getInt("failures"), Jdbc.instant(row, "paused_until"),
                row.getString("last_error"), Jdbc.instant(row, "created_at"));
    }
}
