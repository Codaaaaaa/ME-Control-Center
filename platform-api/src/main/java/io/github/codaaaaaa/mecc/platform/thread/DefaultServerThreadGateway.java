package io.github.codaaaaaa.mecc.platform.thread;

import io.github.codaaaaaa.mecc.core.concurrent.NamedThreadFactory;
import io.github.codaaaaaa.mecc.core.error.ErrorCode;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Version-independent {@link ServerThreadGateway} on top of a platform {@link ServerThreadExecutor}.
 * See the interface for the threading contract.
 */
public final class DefaultServerThreadGateway implements ServerThreadGateway, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultServerThreadGateway.class);

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    public static final int DEFAULT_MAX_PENDING = 256;
    public static final Duration DEFAULT_SLOW_TASK_THRESHOLD = Duration.ofMillis(5);

    private final ServerThreadExecutor serverThread;
    private final Executor completionExecutor;
    private final int maxPending;
    private final Duration defaultTimeout;
    private final long slowTaskThresholdNanos;
    private final ScheduledExecutorService timeoutScheduler =
            Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("ME Control Center-Gateway-Timeout"));
    private static final long SLOW_WARNING_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

    private final Set<PendingTask<?>> pending = ConcurrentHashMap.newKeySet();
    private final java.util.Map<String, Long> lastSlowWarning = new ConcurrentHashMap<>();
    private final AtomicInteger pendingCount = new AtomicInteger();
    private volatile boolean closed;

    /**
     * @param serverThread       platform primitive
     * @param completionExecutor ME Control Center worker executor on which returned futures complete
     */
    public DefaultServerThreadGateway(ServerThreadExecutor serverThread, Executor completionExecutor) {
        this(serverThread, completionExecutor, DEFAULT_MAX_PENDING, DEFAULT_TIMEOUT, DEFAULT_SLOW_TASK_THRESHOLD);
    }

    public DefaultServerThreadGateway(
            ServerThreadExecutor serverThread,
            Executor completionExecutor,
            int maxPending,
            Duration defaultTimeout,
            Duration slowTaskThreshold) {
        this.serverThread = Objects.requireNonNull(serverThread, "serverThread");
        this.completionExecutor = Objects.requireNonNull(completionExecutor, "completionExecutor");
        if (maxPending < 1) {
            throw new IllegalArgumentException("maxPending must be positive");
        }
        this.maxPending = maxPending;
        this.defaultTimeout = Objects.requireNonNull(defaultTimeout, "defaultTimeout");
        this.slowTaskThresholdNanos = slowTaskThreshold.toNanos();
    }

    @Override
    public <T> CompletableFuture<T> call(String name, Supplier<T> task) {
        return call(name, task, defaultTimeout);
    }

    @Override
    public <T> CompletableFuture<T> call(String name, Supplier<T> task, Duration timeout) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(timeout, "timeout");

        if (closed || !serverThread.isRunning()) {
            return CompletableFuture.failedFuture(unavailable(name, null));
        }
        if (serverThread.isServerThread()) {
            // Already on the server thread: queuing would deadlock anyone waiting on the result.
            try {
                return CompletableFuture.completedFuture(runTimed(name, task));
            } catch (Throwable t) {
                return CompletableFuture.failedFuture(t);
            }
        }
        if (pendingCount.incrementAndGet() > maxPending) {
            pendingCount.decrementAndGet();
            return CompletableFuture.failedFuture(new MeccException(ErrorCode.GATEWAY_BUSY,
                    "Too many operations are waiting for the Minecraft server thread"));
        }

        PendingTask<T> pendingTask = new PendingTask<>(name, task);
        pending.add(pendingTask);
        try {
            pendingTask.timeoutHandle = timeoutScheduler.schedule(
                    () -> pendingTask.cancel(new MeccException(ErrorCode.SERVER_THREAD_TIMEOUT,
                            "The Minecraft server thread did not start '" + name + "' within "
                                    + timeout.toMillis() + " ms; it was not executed")),
                    timeout.toNanos(), TimeUnit.NANOSECONDS);
            serverThread.enqueue(pendingTask::runOnServerThread);
        } catch (RuntimeException e) {
            pendingTask.cancel(unavailable(name, e));
        }
        if (closed) {
            // Lost a race with close(): make sure nothing stays pending forever.
            pendingTask.cancel(unavailable(name, null));
        }
        return pendingTask.future;
    }

    @Override
    public boolean isServerThread() {
        return serverThread.isServerThread();
    }

    @Override
    public int pendingTasks() {
        return pendingCount.get();
    }

    /** Fails every task that has not started yet and rejects new calls. Tasks already running finish normally. */
    @Override
    public void close() {
        closed = true;
        for (PendingTask<?> task : pending) {
            task.cancel(unavailable(task.name, null));
        }
        timeoutScheduler.shutdownNow();
    }

    private <T> T runTimed(String name, Supplier<T> task) {
        long start = System.nanoTime();
        try {
            return task.get();
        } finally {
            long end = System.nanoTime();
            long elapsed = end - start;
            if (elapsed > slowTaskThresholdNanos) {
                // At most one warning per task name per interval: periodic tasks must not flood the log.
                Long last = lastSlowWarning.get(name);
                if (last == null || end - last > SLOW_WARNING_INTERVAL_NANOS) {
                    lastSlowWarning.put(name, end);
                    LOGGER.warn("Server-thread task '{}' took {} ms", name, String.format("%.2f", elapsed / 1_000_000.0));
                } else {
                    LOGGER.debug("Server-thread task '{}' took {} ms", name, String.format("%.2f", elapsed / 1_000_000.0));
                }
            }
        }
    }

    private void completeOffServerThread(Runnable completion) {
        try {
            completionExecutor.execute(completion);
        } catch (RejectedExecutionException e) {
            // Worker pool is shutting down; completing inline is the only way to avoid a hung caller.
            completion.run();
        }
    }

    private static MeccException unavailable(String name, Throwable cause) {
        return new MeccException(ErrorCode.SERVER_UNAVAILABLE,
                "The Minecraft server is not available to run '" + name + "'", cause);
    }

    private final class PendingTask<T> {
        private static final int QUEUED = 0;
        private static final int STARTED = 1;
        private static final int CANCELLED = 2;

        private final String name;
        private final Supplier<T> task;
        private final CompletableFuture<T> future = new CompletableFuture<>();
        private final AtomicInteger state = new AtomicInteger(QUEUED);
        private volatile ScheduledFuture<?> timeoutHandle;

        private PendingTask(String name, Supplier<T> task) {
            this.name = name;
            this.task = task;
        }

        private void runOnServerThread() {
            if (!state.compareAndSet(QUEUED, STARTED)) {
                return; // Timed out or cancelled before it started: never execute.
            }
            release();
            T value;
            try {
                value = runTimed(name, task);
            } catch (Throwable t) {
                completeOffServerThread(() -> future.completeExceptionally(t));
                return;
            }
            completeOffServerThread(() -> future.complete(value));
        }

        private void cancel(MeccException reason) {
            if (!state.compareAndSet(QUEUED, CANCELLED)) {
                return;
            }
            release();
            completeOffServerThread(() -> future.completeExceptionally(reason));
        }

        private void release() {
            pending.remove(this);
            pendingCount.decrementAndGet();
            ScheduledFuture<?> handle = timeoutHandle;
            if (handle != null) {
                handle.cancel(false);
            }
        }
    }
}
