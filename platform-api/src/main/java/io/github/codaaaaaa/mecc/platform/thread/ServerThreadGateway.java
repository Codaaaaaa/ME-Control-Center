package io.github.codaaaaaa.mecc.platform.thread;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * The only sanctioned way for non-server threads (HTTP, WebSocket, database, analytics) to read or
 * mutate Minecraft/AE2 state.
 *
 * <p>Contract:
 * <ul>
 *   <li>{@code task} runs on the Minecraft server thread and should only copy state into immutable
 *       DTOs or perform the mutation. No I/O, serialization, or blocking.</li>
 *   <li>The returned future completes on an ME Control Center worker thread, never on the server thread, so
 *       dependent stages (serialization, database writes, HTTP responses) never run inside a tick.
 *       Exception: when called from the server thread, the task runs inline and the future is
 *       already complete.</li>
 *   <li>If the task has not <em>started</em> before the timeout, it is cancelled and guaranteed
 *       never to run; the future fails with {@code SERVER_THREAD_TIMEOUT}. A task that has started
 *       always runs to completion and its result is delivered.</li>
 *   <li>Fails fast with {@code GATEWAY_BUSY} when too many tasks are pending, and with
 *       {@code SERVER_UNAVAILABLE} when the server is not running or the gateway is closed.</li>
 * </ul>
 */
public interface ServerThreadGateway {

    /** Runs {@code task} on the server thread using the default timeout. */
    <T> CompletableFuture<T> call(String name, Supplier<T> task);

    /** Runs {@code task} on the server thread, cancelling it if it has not started within {@code timeout}. */
    <T> CompletableFuture<T> call(String name, Supplier<T> task, Duration timeout);

    boolean isServerThread();

    /** Number of tasks queued but not yet started. */
    int pendingTasks();
}
