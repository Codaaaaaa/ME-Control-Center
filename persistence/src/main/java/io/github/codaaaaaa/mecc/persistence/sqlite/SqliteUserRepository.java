package io.github.codaaaaaa.mecc.persistence.sqlite;

import io.github.codaaaaaa.mecc.core.users.PlayerProfile;
import io.github.codaaaaaa.mecc.core.users.UserRepository;
import io.github.codaaaaaa.mecc.core.users.WebUser;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class SqliteUserRepository implements UserRepository {
    private static final String COLUMNS = "player_uuid, player_name, created_at, last_seen_at";

    private final Jdbc jdbc;

    SqliteUserRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<WebUser> find(UUID playerUuid) {
        return jdbc.queryOne("SELECT " + COLUMNS + " FROM users WHERE player_uuid = ?", SqliteUserRepository::map, playerUuid);
    }

    @Override
    public Optional<WebUser> findByName(String playerName) {
        // Several stale records may share a name after renames; prefer the most recently active one.
        return jdbc.queryOne("SELECT " + COLUMNS + " FROM users WHERE player_name = ? COLLATE NOCASE "
                + "ORDER BY COALESCE(last_seen_at, created_at) DESC LIMIT 1", SqliteUserRepository::map, playerName);
    }

    @Override
    public Map<UUID, WebUser> findAll(Collection<UUID> playerUuids) {
        Map<UUID, WebUser> result = new HashMap<>();
        List<UUID> ids = playerUuids.stream().distinct().toList();
        for (int start = 0; start < ids.size(); start += 500) {
            List<UUID> chunk = ids.subList(start, Math.min(ids.size(), start + 500));
            String placeholders = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            jdbc.query("SELECT " + COLUMNS + " FROM users WHERE player_uuid IN (" + placeholders + ")",
                            SqliteUserRepository::map, chunk.toArray())
                    .forEach(user -> result.put(user.playerUuid(), user));
        }
        return result;
    }

    @Override
    public WebUser upsert(PlayerProfile profile, Instant now) {
        jdbc.update("INSERT INTO users (player_uuid, player_name, created_at) VALUES (?, ?, ?) "
                + "ON CONFLICT (player_uuid) DO UPDATE SET player_name = excluded.player_name",
                profile.uuid(), profile.name(), now);
        return find(profile.uuid()).orElseThrow();
    }

    @Override
    public void touchLastSeen(UUID playerUuid, Instant now) {
        jdbc.update("UPDATE users SET last_seen_at = ? WHERE player_uuid = ?", now, playerUuid);
    }

    private static WebUser map(ResultSet row) throws SQLException {
        return new WebUser(Jdbc.uuid(row, "player_uuid"), row.getString("player_name"),
                Jdbc.instant(row, "created_at"), Jdbc.instant(row, "last_seen_at"));
    }
}
