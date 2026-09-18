package io.github.codaaaaaa.mecc.persistence.sqlite;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Versioned schema migrations. Upgrades apply automatically on startup; users never edit SQL.
 *
 * <p>Rules: never edit or reorder a released migration, only append. Statements are separated by
 * {@code ;} and must not contain semicolons themselves. SQL is kept in Java (not resource files) so it
 * loads identically from any class loader or module layer.
 */
final class Migrations {
    private static final Logger LOGGER = LoggerFactory.getLogger(Migrations.class);

    record Migration(int version, String name, String sql) {
    }

    static final List<Migration> ALL = List.of(
            new Migration(1, "users, devices, networks, members, audit log", """
                    CREATE TABLE users (
                        player_uuid  TEXT PRIMARY KEY,
                        player_name  TEXT NOT NULL,
                        created_at   INTEGER NOT NULL,
                        last_seen_at INTEGER
                    );
                    CREATE INDEX users_player_name ON users (player_name COLLATE NOCASE);

                    CREATE TABLE devices (
                        id           TEXT PRIMARY KEY,
                        player_uuid  TEXT NOT NULL REFERENCES users (player_uuid),
                        name         TEXT NOT NULL,
                        token_hash   TEXT NOT NULL UNIQUE,
                        user_agent   TEXT,
                        created_at   INTEGER NOT NULL,
                        last_used_at INTEGER NOT NULL,
                        last_address TEXT,
                        revoked_at   INTEGER
                    );
                    CREATE INDEX devices_player_uuid ON devices (player_uuid);

                    CREATE TABLE web_networks (
                        id                TEXT PRIMARY KEY,
                        display_name      TEXT NOT NULL,
                        owner_player_uuid TEXT NOT NULL REFERENCES users (player_uuid),
                        created_at        INTEGER NOT NULL,
                        last_seen_at      INTEGER,
                        status            TEXT NOT NULL
                    );

                    CREATE TABLE network_anchors (
                        network_id        TEXT NOT NULL REFERENCES web_networks (id) ON DELETE CASCADE,
                        dimension         TEXT NOT NULL,
                        x                 INTEGER NOT NULL,
                        y                 INTEGER NOT NULL,
                        z                 INTEGER NOT NULL,
                        owner_player_uuid TEXT,
                        created_at        INTEGER NOT NULL,
                        PRIMARY KEY (dimension, x, y, z)
                    );
                    CREATE INDEX network_anchors_network_id ON network_anchors (network_id);

                    CREATE TABLE network_members (
                        network_id  TEXT NOT NULL REFERENCES web_networks (id) ON DELETE CASCADE,
                        player_uuid TEXT NOT NULL REFERENCES users (player_uuid),
                        role        TEXT NOT NULL,
                        added_by    TEXT,
                        added_at    INTEGER NOT NULL,
                        PRIMARY KEY (network_id, player_uuid)
                    );
                    CREATE INDEX network_members_player_uuid ON network_members (player_uuid);

                    CREATE TABLE audit_log (
                        id                INTEGER PRIMARY KEY AUTOINCREMENT,
                        at                INTEGER NOT NULL,
                        actor_player_uuid TEXT,
                        device_id         TEXT,
                        network_id        TEXT,
                        action            TEXT NOT NULL,
                        target            TEXT,
                        result            TEXT NOT NULL,
                        admin_override    INTEGER NOT NULL DEFAULT 0,
                        parameters        TEXT NOT NULL DEFAULT '{}'
                    );
                    CREATE INDEX audit_log_at ON audit_log (at);
                    CREATE INDEX audit_log_network_id ON audit_log (network_id)
                    """),
            new Migration(2, "crafting orders and order history", """
                    CREATE TABLE orders (
                        id                TEXT PRIMARY KEY,
                        network_id        TEXT NOT NULL REFERENCES web_networks (id) ON DELETE CASCADE,
                        creator_uuid      TEXT NOT NULL REFERENCES users (player_uuid),
                        device_id         TEXT,
                        source            TEXT NOT NULL,
                        resource_id       TEXT NOT NULL,
                        resource_names    TEXT NOT NULL DEFAULT '{}',
                        resource_mod_id   TEXT NOT NULL,
                        resource_icon_key TEXT NOT NULL,
                        unit_symbol       TEXT,
                        unit_amount       INTEGER,
                        amount            INTEGER NOT NULL,
                        state             TEXT NOT NULL,
                        job_id            TEXT,
                        cpu_id            TEXT,
                        cpu_name          TEXT,
                        plan_bytes        INTEGER,
                        created_at        INTEGER NOT NULL,
                        started_at        INTEGER,
                        ended_at          INTEGER,
                        last_observed_at  INTEGER,
                        progress_percent  REAL,
                        failure_code      TEXT,
                        failure_message   TEXT
                    );
                    CREATE INDEX orders_network_created ON orders (network_id, created_at);
                    CREATE INDEX orders_state ON orders (state);

                    CREATE TABLE order_events (
                        id                INTEGER PRIMARY KEY AUTOINCREMENT,
                        order_id          TEXT NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
                        at                INTEGER NOT NULL,
                        type              TEXT NOT NULL,
                        actor_player_uuid TEXT,
                        details           TEXT NOT NULL DEFAULT '{}'
                    );
                    CREATE INDEX order_events_order_id ON order_events (order_id, id)
                    """));

    private Migrations() {
    }

    static int latestVersion() {
        return ALL.get(ALL.size() - 1).version();
    }

    /** Applies pending migrations, each in its own transaction. Returns the resulting schema version. */
    static int apply(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                        version    INTEGER PRIMARY KEY,
                        name       TEXT NOT NULL,
                        applied_at INTEGER NOT NULL
                    )""");
        }
        int current = currentVersion(connection);
        if (current > latestVersion()) {
            throw new SQLException("The ME Control Center database has schema version " + current
                    + " but this ME Control Center build only knows up to " + latestVersion()
                    + ". It was created by a newer ME Control Center; downgrading is not supported.");
        }
        for (Migration migration : ALL) {
            if (migration.version() <= current) {
                continue;
            }
            LOGGER.info("Applying ME Control Center database migration {}: {}", migration.version(), migration.name());
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                for (String sql : migration.sql().split(";")) {
                    if (!sql.isBlank()) {
                        statement.executeUpdate(sql);
                    }
                }
                try (var insert = connection.prepareStatement(
                        "INSERT INTO schema_migrations (version, name, applied_at) VALUES (?, ?, ?)")) {
                    insert.setInt(1, migration.version());
                    insert.setString(2, migration.name());
                    insert.setLong(3, System.currentTimeMillis());
                    insert.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
            current = migration.version();
        }
        return current;
    }

    static int currentVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_migrations")) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }
}
