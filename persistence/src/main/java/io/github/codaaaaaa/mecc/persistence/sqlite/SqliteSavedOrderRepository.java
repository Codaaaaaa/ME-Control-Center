package io.github.codaaaaaa.mecc.persistence.sqlite;

import io.github.codaaaaaa.mecc.core.crafting.SavedOrder;
import io.github.codaaaaaa.mecc.core.crafting.SavedOrderRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class SqliteSavedOrderRepository implements SavedOrderRepository {
    private static final String COLUMNS = "id, player_uuid, network_id, name, " + StoredTargets.COLUMNS
            + ", amount, cpu_id, notes, created_at, updated_at";

    private final Jdbc jdbc;

    SqliteSavedOrderRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(SavedOrder order) {
        jdbc.update("INSERT INTO saved_orders (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                StoredTargets.params(new Object[] {order.id(), order.playerUuid(), order.networkId(), order.name()},
                        order.target(), order.amount(), order.cpuId(), order.notes(), order.createdAt(), order.updatedAt()));
    }

    @Override
    public boolean update(SavedOrder order) {
        return jdbc.update("UPDATE saved_orders SET name = ?, amount = ?, cpu_id = ?, notes = ?, updated_at = ? WHERE id = ?",
                order.name(), order.amount(), order.cpuId(), order.notes(), order.updatedAt(), order.id()) > 0;
    }

    @Override
    public Optional<SavedOrder> find(UUID id) {
        return jdbc.queryOne("SELECT " + COLUMNS + " FROM saved_orders WHERE id = ?", SqliteSavedOrderRepository::map, id);
    }

    @Override
    public List<SavedOrder> list(UUID playerUuid, UUID networkId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM saved_orders WHERE player_uuid = ? AND network_id = ? "
                + "ORDER BY name COLLATE NOCASE, created_at", SqliteSavedOrderRepository::map, playerUuid, networkId);
    }

    @Override
    public int countByPlayer(UUID playerUuid) {
        return jdbc.queryOne("SELECT COUNT(*) FROM saved_orders WHERE player_uuid = ?", row -> row.getInt(1), playerUuid)
                .orElse(0);
    }

    @Override
    public boolean delete(UUID id) {
        return jdbc.update("DELETE FROM saved_orders WHERE id = ?", id) > 0;
    }

    @Override
    public int deleteAll(UUID playerUuid, UUID networkId) {
        return jdbc.update("DELETE FROM saved_orders WHERE player_uuid = ? AND network_id = ?", playerUuid, networkId);
    }

    private static SavedOrder map(ResultSet row) throws SQLException {
        return new SavedOrder(Jdbc.uuid(row, "id"), Jdbc.uuid(row, "player_uuid"), Jdbc.uuid(row, "network_id"),
                row.getString("name"), StoredTargets.read(row), row.getLong("amount"), row.getString("cpu_id"),
                row.getString("notes"), Jdbc.instant(row, "created_at"), Jdbc.instant(row, "updated_at"));
    }
}
