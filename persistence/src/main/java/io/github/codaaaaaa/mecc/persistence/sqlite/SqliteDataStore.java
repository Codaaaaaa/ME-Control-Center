package io.github.codaaaaaa.mecc.persistence.sqlite;

import io.github.codaaaaaa.mecc.core.admin.AdminViews.BackupView;
import io.github.codaaaaaa.mecc.core.admin.AdminViews.DatabaseView;
import io.github.codaaaaaa.mecc.core.concurrent.NamedThreadFactory;
import io.github.codaaaaaa.mecc.core.error.ErrorCode;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import io.github.codaaaaaa.mecc.core.persistence.DataStore;
import io.github.codaaaaaa.mecc.core.persistence.Repositories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * SQLite-backed {@link DataStore} (spec section 34): WAL journal, foreign keys enforced, versioned
 * migrations applied on open.
 *
 * <p>A single connection is confined to one {@code ME Control Center-DB} thread, which serializes all access.
 * That keeps SQLite free of lock contention and is ample for ME Control Center's control-plane write volume.
 *
 * <p>Backups are written with {@code VACUUM INTO} on that same thread, so each one is a consistent single-file
 * copy even while the server runs. One is taken automatically before a schema upgrade.
 */
public final class SqliteDataStore implements DataStore, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(SqliteDataStore.class);
    public static final String FILE_NAME = "mecc.db";
    /** Folder next to the database that holds backups. */
    public static final String BACKUP_DIRECTORY = "backups";
    /** Older backups beyond this many are deleted when a new one is written. */
    static final int KEEP_BACKUPS = 10;
    private static final DateTimeFormatter BACKUP_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private final Path file;
    private final ExecutorService executor;
    private final Connection connection;
    private final Repositories repositories;

    private SqliteDataStore(Path file, ExecutorService executor, Connection connection) {
        this.file = file;
        this.executor = executor;
        this.connection = connection;
        this.repositories = new SqliteRepositories(new Jdbc(connection));
    }

    /** Opens (creating if needed) and migrates the database. Blocks; call from an ME Control Center worker thread. */
    public static SqliteDataStore open(Path file) throws IOException, SQLException {
        Files.createDirectories(file.toAbsolutePath().getParent());

        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5_000);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + file.toAbsolutePath());

        Connection connection = dataSource.getConnection();
        try {
            int existing = existingVersion(connection);
            if (existing > 0 && existing < Migrations.latestVersion()) {
                Path backup = writeBackup(connection, file, "pre-v" + Migrations.latestVersion());
                LOGGER.info("Backed up the ME Control Center database to {} before upgrading its schema from version {} to {}",
                        backup, existing, Migrations.latestVersion());
            }
            int version = Migrations.apply(connection);
            LOGGER.info("ME Control Center database ready at {} (schema version {})", file, version);
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
        ExecutorService executor = Executors.newSingleThreadExecutor(new NamedThreadFactory("ME Control Center-DB"));
        return new SqliteDataStore(file, executor, connection);
    }

    public Path file() {
        return file;
    }

    /** Writes a consistent copy of the database into {@link #BACKUP_DIRECTORY}. */
    public CompletableFuture<BackupView> backup() {
        return submit(() -> {
            try {
                Path backup = writeBackup(connection, file, "manual");
                LOGGER.info("Backed up the ME Control Center database to {}", backup);
                return backupView(backup);
            } catch (IOException | SQLException e) {
                throw new IllegalStateException("Could not back up the ME Control Center database", e);
            }
        });
    }

    /** Schema version, size, and backups. */
    public CompletableFuture<DatabaseView> info() {
        return submit(() -> {
            try {
                return new DatabaseView(file.toAbsolutePath().toString(), Migrations.currentVersion(connection),
                        sizeOf(file) + sizeOf(file.resolveSibling(file.getFileName() + "-wal")),
                        backups(file).stream().map(SqliteDataStore::backupView).toList());
            } catch (SQLException e) {
                throw new IllegalStateException("Could not read the ME Control Center database state", e);
            }
        });
    }

    private static int existingVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'schema_migrations'")) {
            if (!rows.next()) {
                return 0;
            }
        }
        return Migrations.currentVersion(connection);
    }

    private static Path writeBackup(Connection connection, Path database, String label) throws IOException, SQLException {
        Path directory = database.toAbsolutePath().resolveSibling(BACKUP_DIRECTORY);
        Files.createDirectories(directory);
        Path target = directory.resolve("mecc-" + BACKUP_TIME.format(Instant.now()) + "-" + label + ".db");
        try (PreparedStatement statement = connection.prepareStatement("VACUUM INTO ?")) {
            statement.setString(1, target.toString());
            statement.executeUpdate();
        }
        List<Path> backups = backups(database);
        for (Path old : backups.subList(Math.min(KEEP_BACKUPS, backups.size()), backups.size())) {
            Files.deleteIfExists(old);
        }
        return target;
    }

    /** Backup files, newest first (their names sort by time). */
    private static List<Path> backups(Path database) {
        Path directory = database.toAbsolutePath().resolveSibling(BACKUP_DIRECTORY);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> backups = new ArrayList<>(files
                    .filter(path -> path.getFileName().toString().matches("mecc-\\d{8}-\\d{6}-\\d{3}-.*\\.db"))
                    .toList());
            backups.sort(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed());
            return backups;
        } catch (IOException e) {
            LOGGER.warn("Could not list ME Control Center database backups in {}", directory, e);
            return List.of();
        }
    }

    private static BackupView backupView(Path backup) {
        String name = backup.getFileName().toString();
        Instant createdAt = BACKUP_TIME.parse(name.substring(5, 24), Instant::from);
        return new BackupView(name, createdAt, sizeOf(backup));
    }

    private static long sizeOf(Path path) {
        try {
            return Files.exists(path) ? Files.size(path) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public <T> CompletableFuture<T> read(Function<Repositories, T> work) {
        return submit(() -> work.apply(repositories));
    }

    @Override
    public <T> CompletableFuture<T> write(Function<Repositories, T> work) {
        return submit(() -> {
            try {
                connection.setAutoCommit(false);
                T result;
                try {
                    result = work.apply(repositories);
                    connection.commit();
                } catch (Throwable t) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackError) {
                        t.addSuppressed(rollbackError);
                    }
                    throw t;
                }
                return result;
            } catch (SQLException e) {
                throw new IllegalStateException("ME Control Center database transaction failed", e);
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException e) {
                    LOGGER.warn("Could not restore auto-commit on the ME Control Center database connection", e);
                }
            }
        });
    }

    private <T> CompletableFuture<T> submit(java.util.function.Supplier<T> task) {
        try {
            return CompletableFuture.supplyAsync(task, executor);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(
                    new MeccException(ErrorCode.SERVICE_UNAVAILABLE, "The ME Control Center database is closed", e));
        }
    }

    /** Waits for queued work, then closes the connection. */
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                LOGGER.warn("ME Control Center database work did not finish within 10 s; closing anyway");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        try {
            connection.close();
        } catch (SQLException e) {
            LOGGER.warn("Error closing the ME Control Center database", e);
        }
    }
}
