package io.github.codaaaaaa.mecc.persistence.sqlite;

import io.github.codaaaaaa.mecc.core.persistence.DuplicateKeyException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

/**
 * Minimal JDBC helper. Instants are stored as epoch milliseconds, UUIDs as text, enums by name,
 * booleans as 0/1. SQL errors become unchecked; unique violations become {@link DuplicateKeyException}.
 */
final class Jdbc {
    private final Connection connection;

    Jdbc(Connection connection) {
        this.connection = connection;
    }

    @FunctionalInterface
    interface RowMapper<T> {
        T map(ResultSet row) throws SQLException;
    }

    int update(String sql, Object... params) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw translate(e);
        }
    }

    <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet rows = statement.executeQuery()) {
                List<T> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(mapper.map(rows));
                }
                return result;
            }
        } catch (SQLException e) {
            throw translate(e);
        }
    }

    <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        List<T> rows = query(sql, mapper, params);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    static Instant instant(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    static UUID uuid(ResultSet row, String column) throws SQLException {
        String value = row.getString(column);
        return value == null ? null : UUID.fromString(value);
    }

    private static void bind(PreparedStatement statement, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object value = params[i];
            int index = i + 1;
            if (value == null) {
                statement.setNull(index, Types.NULL);
            } else if (value instanceof Instant instant) {
                statement.setLong(index, instant.toEpochMilli());
            } else if (value instanceof UUID uuid) {
                statement.setString(index, uuid.toString());
            } else if (value instanceof Enum<?> constant) {
                statement.setString(index, constant.name());
            } else if (value instanceof Boolean bool) {
                statement.setInt(index, bool ? 1 : 0);
            } else if (value instanceof Integer number) {
                statement.setInt(index, number);
            } else if (value instanceof Long number) {
                statement.setLong(index, number);
            } else if (value instanceof Double number) {
                statement.setDouble(index, number);
            } else if (value instanceof String text) {
                statement.setString(index, text);
            } else {
                throw new IllegalArgumentException("Unsupported SQL parameter type: " + value.getClass());
            }
        }
    }

    private static RuntimeException translate(SQLException e) {
        if (e instanceof SQLiteException sqlite) {
            SQLiteErrorCode code = sqlite.getResultCode();
            if (code == SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE || code == SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY) {
                return new DuplicateKeyException("Unique constraint violated", e);
            }
        }
        return new IllegalStateException("ME Control Center database error: " + e.getMessage(), e);
    }
}
