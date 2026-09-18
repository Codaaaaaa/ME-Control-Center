package io.github.codaaaaaa.mecc.platform.thread;

/**
 * Minimal platform primitive for running code on the Minecraft server thread.
 *
 * <p>Implemented by each platform adapter. ME Control Center code should not use this directly; use
 * {@link ServerThreadGateway}, which adds backpressure, timeouts, and off-thread completion.
 */
public interface ServerThreadExecutor {

    /** Whether the current thread is the Minecraft server thread. */
    boolean isServerThread();

    /** Whether the server is running and will process queued tasks. */
    boolean isRunning();

    /**
     * Queues a task to run on the server thread between ticks. Must never run the task on the
     * calling thread, even if the server is stopping.
     */
    void enqueue(Runnable task);
}
