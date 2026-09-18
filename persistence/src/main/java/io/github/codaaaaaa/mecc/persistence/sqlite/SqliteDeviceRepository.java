package io.github.codaaaaaa.mecc.persistence.sqlite;

import io.github.codaaaaaa.mecc.core.auth.Device;
import io.github.codaaaaaa.mecc.core.auth.DeviceRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class SqliteDeviceRepository implements DeviceRepository {
    private static final String COLUMNS =
            "id, player_uuid, name, user_agent, created_at, last_used_at, last_address, revoked_at";

    private final Jdbc jdbc;

    SqliteDeviceRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Device device, String tokenHash) {
        jdbc.update("INSERT INTO devices (" + COLUMNS + ", token_hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                device.id(), device.playerUuid(), device.name(), device.userAgent(), device.createdAt(),
                device.lastUsedAt(), device.lastAddress(), device.revokedAt(), tokenHash);
    }

    @Override
    public Optional<Device> findActiveByTokenHash(String tokenHash) {
        return jdbc.queryOne("SELECT " + COLUMNS + " FROM devices WHERE token_hash = ? AND revoked_at IS NULL",
                SqliteDeviceRepository::map, tokenHash);
    }

    @Override
    public Optional<Device> find(String id) {
        return jdbc.queryOne("SELECT " + COLUMNS + " FROM devices WHERE id = ?", SqliteDeviceRepository::map, id);
    }

    @Override
    public List<Device> listActive(UUID playerUuid) {
        return jdbc.query("SELECT " + COLUMNS + " FROM devices WHERE player_uuid = ? AND revoked_at IS NULL "
                + "ORDER BY last_used_at DESC", SqliteDeviceRepository::map, playerUuid);
    }

    @Override
    public void touch(String id, Instant usedAt, String address) {
        jdbc.update("UPDATE devices SET last_used_at = ?, last_address = ? WHERE id = ?", usedAt, address, id);
    }

    @Override
    public void rename(String id, String name) {
        jdbc.update("UPDATE devices SET name = ? WHERE id = ?", name, id);
    }

    @Override
    public boolean revoke(String id, Instant now) {
        return jdbc.update("UPDATE devices SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL", now, id) > 0;
    }

    @Override
    public int revokeAll(UUID playerUuid, String keepId, Instant now) {
        if (keepId == null) {
            return jdbc.update("UPDATE devices SET revoked_at = ? WHERE player_uuid = ? AND revoked_at IS NULL",
                    now, playerUuid);
        }
        return jdbc.update("UPDATE devices SET revoked_at = ? WHERE player_uuid = ? AND revoked_at IS NULL AND id <> ?",
                now, playerUuid, keepId);
    }

    private static Device map(ResultSet row) throws SQLException {
        return new Device(row.getString("id"), Jdbc.uuid(row, "player_uuid"), row.getString("name"),
                row.getString("user_agent"), Jdbc.instant(row, "created_at"), Jdbc.instant(row, "last_used_at"),
                row.getString("last_address"), Jdbc.instant(row, "revoked_at"));
    }
}
