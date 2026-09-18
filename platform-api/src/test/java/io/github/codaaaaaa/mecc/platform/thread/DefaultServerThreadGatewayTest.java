package io.github.codaaaaaa.mecc.platform.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.codaaaaaa.mecc.core.concurrent.NamedThreadFactory;
import io.github.codaaaaaa.mecc.core.error.ErrorCode;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultServerThreadGatewayTest {

    private FakeServerThread server;
    private ExecutorService workers;
    private DefaultServerThreadGateway gateway;

    @BeforeEach
    void setUp() {
        server = new FakeServerThread();
        workers = Executors.newFixedThreadPool(2, new NamedThreadFactory("test-worker"));
        gateway = new DefaultServerThreadGateway(server, workers, 2, Duration.ofSeconds(5), Duration.ofSeconds(1));
    }

    @AfterEach
    void tearDown() {
        gateway.close();
        server.executor.shutdownNow();
        workers.shutdownNow();
    }

    @Test
    void runsTaskOnServerThreadAndCompletesOnWorker() throws Exception {
        CountDownLatch release = server.block();
        AtomicReference<Thread> taskThread = new AtomicReference<>();
        AtomicReference<Thread> completionThread = new AtomicReference<>();

        CompletableFuture<String> future = gateway.call("probe", () -> {
            taskThread.set(Thread.currentThread());
            return "ok";
        });
        CompletableFuture<String> observed = future.whenComplete((v, e) -> completionThread.set(Thread.currentThread()));
        release.countDown();

        assertEquals("ok", observed.get(5, TimeUnit.SECONDS));
        assertSame(server.thread, taskThread.get());
        assertTrue(completionThread.get().getName().startsWith("test-worker"), completionThread.get().getName());
        assertEquals(0, gateway.pendingTasks());
    }

    @Test
    void runsInlineWhenAlreadyOnServerThread() throws Exception {
        CompletableFuture<Boolean> inlineWasDone = CompletableFuture.supplyAsync(
                () -> gateway.call("nested", () -> 42).isDone(), server.executor);
        assertTrue(inlineWasDone.get(5, TimeUnit.SECONDS));
    }

    @Test
    void rejectsWhenTooManyTasksArePending() throws Exception {
        CountDownLatch release = server.block();
        CompletableFuture<Integer> first = gateway.call("a", () -> 1);
        CompletableFuture<Integer> second = gateway.call("b", () -> 2);

        assertEquals(ErrorCode.GATEWAY_BUSY, errorCode(gateway.call("c", () -> 3)));

        release.countDown();
        assertEquals(1, first.get(5, TimeUnit.SECONDS));
        assertEquals(2, second.get(5, TimeUnit.SECONDS));
    }

    @Test
    void timedOutTaskNeverRuns() throws Exception {
        CountDownLatch release = server.block();
        AtomicBoolean ran = new AtomicBoolean();

        CompletableFuture<Boolean> future = gateway.call("slow-queue", () -> ran.getAndSet(true), Duration.ofMillis(50));
        assertEquals(ErrorCode.SERVER_THREAD_TIMEOUT, errorCode(future));

        release.countDown();
        server.drain();
        assertFalse(ran.get());
        assertEquals(0, gateway.pendingTasks());
    }

    @Test
    void startedTaskIsNotCutOffByTimeout() throws Exception {
        CompletableFuture<String> future = gateway.call("long-running", () -> {
            sleep(400);
            return "finished";
        }, Duration.ofMillis(100));
        assertEquals("finished", future.get(5, TimeUnit.SECONDS));
    }

    @Test
    void closeFailsPendingTasksWithoutRunningThem() throws Exception {
        CountDownLatch release = server.block();
        AtomicBoolean ran = new AtomicBoolean();
        CompletableFuture<Boolean> future = gateway.call("pending", () -> ran.getAndSet(true));

        gateway.close();

        assertEquals(ErrorCode.SERVER_UNAVAILABLE, errorCode(future));
        assertEquals(ErrorCode.SERVER_UNAVAILABLE, errorCode(gateway.call("after-close", () -> true)));
        release.countDown();
        server.drain();
        assertFalse(ran.get());
    }

    @Test
    void rejectsWhenServerIsNotRunning() throws Exception {
        server.running = false;
        assertEquals(ErrorCode.SERVER_UNAVAILABLE, errorCode(gateway.call("stopped", () -> 1)));
    }

    @Test
    void propagatesTaskExceptions() {
        CompletableFuture<Object> future = gateway.call("boom", () -> {
            throw new IllegalStateException("boom");
        });
        ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, e.getCause());
    }

    private static ErrorCode errorCode(CompletableFuture<?> future) throws Exception {
        ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        return assertInstanceOf(MeccException.class, e.getCause()).code();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Single-thread executor standing in for the Minecraft server thread. */
    private static final class FakeServerThread implements ServerThreadExecutor {
        final ExecutorService executor;
        volatile Thread thread;
        volatile boolean running = true;

        FakeServerThread() {
            executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread t = new Thread(runnable, "fake-server-thread");
                t.setDaemon(true);
                thread = t;
                return t;
            });
        }

        /** Occupies the server thread until the returned latch is released. */
        CountDownLatch block() throws InterruptedException {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            executor.execute(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            return release;
        }

        /** Waits until every task queued so far has been processed. */
        void drain() throws Exception {
            executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
        }

        @Override
        public boolean isServerThread() {
            return Thread.currentThread() == thread;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public void enqueue(Runnable task) {
            executor.execute(task);
        }
    }
}
