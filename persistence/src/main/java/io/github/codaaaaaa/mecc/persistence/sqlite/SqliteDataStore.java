package io.github.codaaaaaa.mecc.persistence.sqlite;

import io.github.codaaaaaa.mecc.core.concurrent.NamedThreadFactory;
import io.github.codaaaaaa.mecc.core.error.ErrorCode;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import io.github.codaaaaaa.mecc.core.persistence.DataStore;
import io.github.codaaaaaa.mecc.core.persistence.Repositories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
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
 */
public final class SqliteDataStore implements DataStore, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(SqliteDataStore.class);
    public static final String FILE_NAME = "mecc.db";

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
