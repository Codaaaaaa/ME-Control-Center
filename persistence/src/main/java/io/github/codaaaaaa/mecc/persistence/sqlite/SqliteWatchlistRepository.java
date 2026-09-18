package io.github.codaaaaaa.mecc.persistence.sqlite;

import io.github.codaaaaaa.mecc.core.crafting.OrderTarget;
import io.github.codaaaaaa.mecc.core.insights.WatchEntry;
import io.github.codaaaaaa.mecc.core.insights.WatchlistRepository;
import io.github.codaaaaaa.mecc.core.resources.ResourceDescriptor.ResourceUnit;
import io.github.codaaaaaa.mecc.core.resources.ResourceId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class SqliteWatchlistRepository implements WatchlistRepository {
    private static final String COLUMNS = "id, player_uuid, network_id, resource_id, resource_names, resource_mod_id, "
            + "resource_icon_key, unit_symbol, unit_amount, created_at";

    private final Jdbc jdbc;

    SqliteWatchlistRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(WatchEntry entry) {
        OrderTarget resource = entry.resource();
        ResourceUnit unit = resource.unit();
        jdbc.update("INSERT INTO watchlist_entries (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                entry.id(), entry.playerUuid(), entry.networkId(), resource.resourceId().toString(),
                StringMapJson.write(resource.names()), resource.modId(), resource.iconKey(),
                unit == null ? null : unit.symbol(), unit == null ? null : unit.amountPerUnit(), entry.createdAt());
    }

    @Override
    public Optional<WatchEntry> find(UUID id) {
        return jdbc.queryOne("SELECT " + COLUMNS + " FROM watchlist_entries WHERE id = ?",
                SqliteWatchlistRepository::map, id);
    }

    @Override
    public Optional<WatchEntry> find(UUID playerUuid, UUID networkId, ResourceId resource) {
        return jdbc.queryOne("SELECT " + COLUMNS + " FROM watchlist_entries "
                        + "WHERE player_uuid = ? AND network_id = ? AND resource_id = ?",
                SqliteWatchlistRepository::map, playerUuid, networkId, resource.toString());
    }

    @Override
    public List<WatchEntry> list(UUID playerUuid, UUID networkId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM watchlist_entries WHERE player_uuid = ? AND network_id = ? "
                + "ORDER BY created_at, id", SqliteWatchlistRepository::map, playerUuid, networkId);
    }

    @Override
    public int countByPlayer(UUID playerUuid) {
        return jdbc.queryOne("SELECT COUNT(*) FROM watchlist_entries WHERE player_uuid = ?",
                row -> row.getInt(1), playerUuid).orElse(0);
    }

    @Override
    public boolean delete(UUID id) {
        return jdbc.update("DELETE FROM watchlist_entries WHERE id = ?", id) > 0;
    }

    @Override
    public int deleteAll(UUID playerUuid, UUID networkId) {
        return jdbc.update("DELETE FROM watchlist_entries WHERE player_uuid = ? AND network_id = ?", playerUuid, networkId);
    }

    @Override
    public Map<UUID, Set<ResourceId>> watchedSeries() {
        Map<UUID, Set<ResourceId>> series = new HashMap<>();
        jdbc.query("SELECT DISTINCT network_id, resource_id FROM watchlist_entries", row -> {
            UUID networkId = UUID.fromString(row.getString("network_id"));
            ResourceId.parse(row.getString("resource_id")).ifPresent(resource ->
                    series.computeIfAbsent(networkId, id -> new HashSet<>()).add(resource));
            return null;
        });
        return series;
    }

    private static WatchEntry map(ResultSet row) throws SQLException {
        String unitSymbol = row.getString("unit_symbol");
        int unitAmount = row.getInt("unit_amount");
        ResourceUnit unit = unitSymbol == null || row.wasNull() ? null : new ResourceUnit(unitSymbol, unitAmount);
        OrderTarget resource = new OrderTarget(ResourceId.parse(row.getString("resource_id")).orElseThrow(),
                StringMapJson.read(row.getString("resource_names")), row.getString("resource_mod_id"),
                row.getString("resource_icon_key"), unit);
        return new WatchEntry(Jdbc.uuid(row, "id"), Jdbc.uuid(row, "player_uuid"), Jdbc.uuid(row, "network_id"), resource,
                Jdbc.instant(row, "created_at"));
    }
}
