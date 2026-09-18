package io.github.codaaaaaa.mecc.persistence.sqlite;

import io.github.codaaaaaa.mecc.core.alerts.AlertEvent;
import io.github.codaaaaaa.mecc.core.alerts.AlertRepository;
import io.github.codaaaaaa.mecc.core.alerts.AlertRule;
import io.github.codaaaaaa.mecc.core.alerts.AlertSettings;
import io.github.codaaaaaa.mecc.core.alerts.AlertState;
import io.github.codaaaaaa.mecc.core.alerts.AlertType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class SqliteAlertRepository implements AlertRepository {
    private static final String RULE_COLUMNS = "id, player_uuid, network_id, type, " + StoredTargets.COLUMNS
            + ", threshold, window_minutes, cooldown_minutes, enabled, state, notified_at, created_at";
    private static final String EVENT_COLUMNS = "id, rule_id, player_uuid, network_id, type, kind, at, "
            + StoredTargets.COLUMNS + ", value, threshold, order_id";

    private final Jdbc jdbc;

    SqliteAlertRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertRule(AlertRule rule) {
        jdbc.update("INSERT INTO alert_rules (" + RULE_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                StoredTargets.params(new Object[] {rule.id(), rule.playerUuid(), rule.networkId(), rule.type()},
                        rule.resource(), rule.threshold(), rule.window(), rule.cooldown(), rule.enabled(), rule.state(),
                        rule.notifiedAt(), rule.createdAt()));
    }

    @Override
    public boolean updateRule(AlertRule rule) {
        return jdbc.update("UPDATE alert_rules SET threshold = ?, window_minutes = ?, cooldown_minutes = ?, enabled = ?, "
                        + "state = ?, notified_at = ? WHERE id = ?",
                rule.threshold(), rule.window(), rule.cooldown(), rule.enabled(), rule.state(), rule.notifiedAt(), rule.id()) > 0;
    }

    @Override
    public boolean setState(UUID id, AlertState state, Instant notifiedAt) {
        return jdbc.update("UPDATE alert_rules SET state = ?, notified_at = ? WHERE id = ?", state, notifiedAt, id) > 0;
    }

    @Override
    public Optional<AlertRule> findRule(UUID id) {
        return jdbc.queryOne("SELECT " + RULE_COLUMNS + " FROM alert_rules WHERE id = ?", SqliteAlertRepository::rule, id);
    }

    @Override
    public List<AlertRule> rules(UUID playerUuid, UUID networkId) {
        return jdbc.query("SELECT " + RULE_COLUMNS + " FROM alert_rules WHERE player_uuid = ? AND network_id = ? "
                + "ORDER BY created_at, id", SqliteAlertRepository::rule, playerUuid, networkId);
    }

    @Override
    public List<AlertRule> enabledRules() {
        return jdbc.query("SELECT " + RULE_COLUMNS + " FROM alert_rules WHERE enabled = 1", SqliteAlertRepository::rule);
    }

    @Override
    public int countRules(UUID playerUuid) {
        return jdbc.queryOne("SELECT COUNT(*) FROM alert_rules WHERE player_uuid = ?", row -> row.getInt(1), playerUuid)
                .orElse(0);
    }

    @Override
    public boolean deleteRule(UUID id) {
        return jdbc.update("DELETE FROM alert_rules WHERE id = ?", id) > 0;
    }

    @Override
    public int deleteRules(UUID playerUuid, UUID networkId) {
        return jdbc.update("DELETE FROM alert_rules WHERE player_uuid = ? AND network_id = ?", playerUuid, networkId);
    }

    @Override
    public long appendEvent(AlertEvent event) {
        jdbc.update("INSERT INTO alert_events (" + EVENT_COLUMNS.substring("id, ".length()) + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                StoredTargets.params(new Object[] {event.ruleId(), event.playerUuid(), event.networkId(), event.type(),
                        event.kind(), event.at()}, event.resource(), event.value(), event.threshold(), event.orderId()));
        // One connection, confined to the database thread: the last row ID is this insert's.
        return jdbc.queryOne("SELECT last_insert_rowid()", row -> row.getLong(1)).orElseThrow();
    }

    @Override
    public List<AlertEvent> events(UUID playerUuid, UUID networkId, Long beforeId, int limit) {
        return jdbc.query("SELECT " + EVENT_COLUMNS + " FROM alert_events WHERE player_uuid = ? "
                        + "AND (? IS NULL OR network_id = ?) AND (? IS NULL OR id < ?) ORDER BY id DESC LIMIT ?",
                SqliteAlertRepository::event, playerUuid, networkId, networkId, beforeId, beforeId, limit);
    }

    @Override
    public int purgeEvents(Instant before) {
        return jdbc.update("DELETE FROM alert_events WHERE at < ?", before);
    }

    @Override
    public Optional<AlertSettings> settings(UUID playerUuid) {
        return jdbc.queryOne("SELECT player_uuid, discord_webhook_url, webhook_url, locale FROM alert_settings "
                        + "WHERE player_uuid = ?",
                row -> new AlertSettings(Jdbc.uuid(row, "player_uuid"), row.getString("discord_webhook_url"),
                        row.getString("webhook_url"), row.getString("locale")), playerUuid);
    }

    @Override
    public void putSettings(AlertSettings settings) {
        jdbc.update("INSERT INTO alert_settings (player_uuid, discord_webhook_url, webhook_url, locale) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (player_uuid) DO UPDATE SET discord_webhook_url = excluded.discord_webhook_url, "
                        + "webhook_url = excluded.webhook_url, locale = excluded.locale",
                settings.playerUuid(), settings.discordWebhookUrl(), settings.webhookUrl(), settings.locale());
    }

    private static AlertRule rule(ResultSet row) throws SQLException {
        long threshold = row.getLong("threshold");
        Long thresholdOrNull = row.wasNull() ? null : threshold;
        int window = row.getInt("window_minutes");
        Integer windowOrNull = row.wasNull() ? null : window;
        return new AlertRule(Jdbc.uuid(row, "id"), Jdbc.uuid(row, "player_uuid"), Jdbc.uuid(row, "network_id"),
                AlertType.valueOf(row.getString("type")), StoredTargets.read(row), thresholdOrNull, windowOrNull,
                row.getInt("cooldown_minutes"), row.getInt("enabled") != 0, AlertState.valueOf(row.getString("state")),
                Jdbc.instant(row, "notified_at"), Jdbc.instant(row, "created_at"));
    }

    private static AlertEvent event(ResultSet row) throws SQLException {
        long value = row.getLong("value");
        Long valueOrNull = row.wasNull() ? null : value;
        long threshold = row.getLong("threshold");
        Long thresholdOrNull = row.wasNull() ? null : threshold;
        return new AlertEvent(row.getLong("id"), Jdbc.uuid(row, "rule_id"), Jdbc.uuid(row, "player_uuid"),
                Jdbc.uuid(row, "network_id"), AlertType.valueOf(row.getString("type")),
                AlertEvent.Kind.valueOf(row.getString("kind")), Jdbc.instant(row, "at"), StoredTargets.read(row),
                valueOrNull, thresholdOrNull, Jdbc.uuid(row, "order_id"));
    }
}
