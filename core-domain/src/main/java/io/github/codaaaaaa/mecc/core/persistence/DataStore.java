package io.github.codaaaaaa.mecc.core.persistence;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Asynchronous access to ME Control Center's persistent state. Work runs on dedicated database threads, never on
 * the Minecraft server thread or an HTTP thread.
 */
public interface DataStore {

    /** Runs {@code work} without a write transaction. */
    <T> CompletableFuture<T> read(Function<Repositories, T> work);

    /** Runs {@code work} in a single transaction; any exception rolls everything back. */
    <T> CompletableFuture<T> write(Function<Repositories, T> work);
}
