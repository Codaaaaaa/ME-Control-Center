package io.github.codaaaaaa.mecc.forge;

import io.github.codaaaaaa.mecc.platform.thread.ServerThreadExecutor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;

/**
 * Queues work onto {@link MinecraftServer}'s task queue.
 *
 * <p>Deliberately uses {@code tell(new TickTask(...))} rather than {@code execute(...)}: once the
 * server has stopped, {@code MinecraftServer.execute} runs the task <em>inline on the calling
 * thread</em>, which would let an HTTP thread touch world state. {@code tell} always queues.
 */
final class ForgeServerThreadExecutor implements ServerThreadExecutor {
    private final MinecraftServer server;

    ForgeServerThreadExecutor(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public boolean isServerThread() {
        return server.isSameThread();
    }

    @Override
    public boolean isRunning() {
        return server.isRunning();
    }

    @Override
    public void enqueue(Runnable task) {
        server.tell(new TickTask(server.getTickCount(), task));
    }
}
