package io.github.codaaaaaa.mecc.persistence.sqlite;

import io.github.codaaaaaa.mecc.core.networks.BlockLocation;
import io.github.codaaaaaa.mecc.core.networks.NetworkAnchor;
import io.github.codaaaaaa.mecc.core.networks.NetworkMember;
import io.github.codaaaaaa.mecc.core.networks.NetworkRecordStatus;
import io.github.codaaaaaa.mecc.core.networks.NetworkRepository;
import io.github.codaaaaaa.mecc.core.networks.WebNetwork;
import io.github.codaaaaaa.mecc.core.permissions.NetworkRole;
import io.github.codaaaaaa.mecc.core.persistence.DuplicateKeyException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class SqliteNetworkRepository implements NetworkRepository {
    private static final String NETWORK_COLUMNS = "id, display_name, owner_player_uuid, created_at, last_seen_at, status";
    private static final String ANCHOR_COLUMNS = "network_id, dimension, x, y, z, owner_player_uuid, created_at";

    private final Jdbc jdbc;

    SqliteNetworkRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<WebNetwork> listAll() {
        Map<UUID, List<NetworkAnchor>> anchors = new HashMap<>();
        jdbc.query("SELECT " + ANCHOR_COLUMNS + " FROM network_anchors ORDER BY created_at", row -> {
            anchors.computeIfAbsent(Jdbc.uuid(row, "network_id"), id -> new ArrayList<>()).add(mapAnchor(row));
            return null;
        });
        return jdbc.query("SELECT " + NETWORK_COLUMNS + " FROM web_networks ORDER BY created_at",
                row -> mapNetwork(row, anchors.getOrDefault(Jdbc.uuid(row, "id"), List.of())));
    }

    @Override
    public Optional<WebNetwork> find(UUID id) {
        List<NetworkAnchor> anchors = jdbc.query(
                "SELECT " + ANCHOR_COLUMNS + " FROM network_anchors WHERE network_id = ? ORDER BY created_at",
                SqliteNetworkRepository::mapAnchor, id);
        return jdbc.queryOne("SELECT " + NETWORK_COLUMNS + " FROM web_networks WHERE id = ?",
                row -> mapNetwork(row, anchors), id);
    }

    @Override
    public void insert(WebNetwork network) {
        jdbc.update("INSERT INTO web_networks (" + NETWORK_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?)",
                network.id(), network.displayName(), network.ownerPlayerUuid(), network.createdAt(),
                network.lastSeenAt(), network.status());
        for (NetworkAnchor anchor : network.anchors()) {
            insertAnchor(network.id(), anchor);
        }
    }

    @Override
    public void rename(UUID id, String displayName) {
        jdbc.update("UPDATE web_networks SET display_name = ? WHERE id = ?", displayName, id);
    }

    @Override
    public boolean delete(UUID id) {
        return jdbc.update("DELETE FROM web_networks WHERE id = ?", id) > 0;
    }

    @Override
    public void updateObservation(UUID id, NetworkRecordStatus status, Instant lastSeenAt) {
        jdbc.update("UPDATE web_networks SET status = ?, last_seen_at = COALESCE(?, last_seen_at) WHERE id = ?",
                status, lastSeenAt, id);
    }

    @Override
    public boolean addAnchor(UUID networkId, NetworkAnchor anchor) {
        try {
            insertAnchor(networkId, anchor);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public void removeAnchor(UUID networkId, BlockLocation location) {
        jdbc.update("DELETE FROM network_anchors WHERE network_id = ? AND dimension = ? AND x = ? AND y = ? AND z = ?",
                networkId, location.dimension(), location.x(), location.y(), location.z());
    }

    @Override
    public Optional<NetworkRole> memberRole(UUID networkId, UUID playerUuid) {
        return jdbc.queryOne("SELECT role FROM network_members WHERE network_id = ? AND player_uuid = ?",
                row -> NetworkRole.valueOf(row.getString("role")), networkId, playerUuid);
    }

    @Override
    public Map<UUID, NetworkRole> membershipsOf(UUID playerUuid) {
        Map<UUID, NetworkRole> result = new LinkedHashMap<>();
        jdbc.query("SELECT network_id, role FROM network_members WHERE player_uuid = ?", row -> {
            result.put(Jdbc.uuid(row, "network_id"), NetworkRole.valueOf(row.getString("role")));
            return null;
        }, playerUuid);
        return result;
    }

    @Override
    public List<NetworkMember> members(UUID networkId) {
        return jdbc.query("SELECT network_id, player_uuid, role, added_by, added_at FROM network_members "
                        + "WHERE network_id = ? ORDER BY added_at",
                row -> new NetworkMember(Jdbc.uuid(row, "network_id"), Jdbc.uuid(row, "player_uuid"),
                        NetworkRole.valueOf(row.getString("role")), Jdbc.uuid(row, "added_by"),
                        Jdbc.instant(row, "added_at")),
                networkId);
    }

    @Override
    public void upsertMember(NetworkMember member) {
        jdbc.update("INSERT INTO network_members (network_id, player_uuid, role, added_by, added_at) VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (network_id, player_uuid) DO UPDATE SET role = excluded.role",
                member.networkId(), member.playerUuid(), member.role(), member.addedBy(), member.addedAt());
    }

    @Override
    public boolean removeMember(UUID networkId, UUID playerUuid) {
        return jdbc.update("DELETE FROM network_members WHERE network_id = ? AND player_uuid = ?", networkId, playerUuid) > 0;
    }

    private void insertAnchor(UUID networkId, NetworkAnchor anchor) {
        BlockLocation location = anchor.location();
        jdbc.update("INSERT INTO network_anchors (" + ANCHOR_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?)",
                networkId, location.dimension(), location.x(), location.y(), location.z(),
                anchor.ownerUuid(), anchor.createdAt());
    }

    private static NetworkAnchor mapAnchor(ResultSet row) throws SQLException {
        return new NetworkAnchor(
                new BlockLocation(row.getString("dimension"), row.getInt("x"), row.getInt("y"), row.getInt("z")),
                Jdbc.uuid(row, "owner_player_uuid"), Jdbc.instant(row, "created_at"));
    }

    private static WebNetwork mapNetwork(ResultSet row, List<NetworkAnchor> anchors) throws SQLException {
        return new WebNetwork(Jdbc.uuid(row, "id"), row.getString("display_name"), Jdbc.uuid(row, "owner_player_uuid"),
                Jdbc.instant(row, "created_at"), Jdbc.instant(row, "last_seen_at"),
                NetworkRecordStatus.valueOf(row.getString("status")), anchors);
    }
}
