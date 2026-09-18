package io.github.codaaaaaa.mecc.exporter;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.codaaaaaa.mecc.exporter.AnimationClip.Clip;
import io.github.codaaaaaa.mecc.exporter.AnimationClip.Motion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes the content pack zip on its own thread, so PNG encoding and disk I/O never stall the game. Tasks
 * run in submission order; the zip only becomes visible under its final name once it is complete.
 */
final class ContentPackWriter implements AutoCloseable {
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ME Control Center-Exporter-IO");
        thread.setDaemon(true);
        return thread;
    });
    private final Path temporary;
    private final ZipOutputStream zip;
    private final AtomicInteger pendingBatches = new AtomicInteger();
    private final AtomicInteger written = new AtomicInteger();
    private final AtomicInteger animated = new AtomicInteger();
    private final AtomicInteger blank = new AtomicInteger();
    private volatile Throwable failure;

    ContentPackWriter(Path directory) throws IOException {
        Files.createDirectories(directory);
        temporary = Files.createTempFile(directory, "mecc-content-pack-", ".zip.part");
        zip = new ZipOutputStream(Files.newOutputStream(temporary));
    }

    /** Batches handed over but not yet written; the renderer waits while this is high, bounding memory. */
    int pendingBatches() {
        return pendingBatches.get();
    }

    int iconsWritten() {
        return written.get();
    }

    /** Written icons that move. */
    int iconsAnimated() {
        return animated.get();
    }

    /** The first write failure, or {@code null}; once set, nothing more is written. */
    Throwable failure() {
        return failure;
    }

    /** Icons that rendered as nothing at all; left out so the server renders them itself. */
    int iconsBlank() {
        return blank.get();
    }

    /** @param atlas closed by this writer once its icons are written */
    void writeIcons(NativeImage atlas, int columns, List<IconSource> batch) {
        writeBatch(() -> {
            for (int i = 0; i < batch.size(); i++) {
                writeIcon(batch.get(i).iconKey(), List.of(IconAtlasRenderer.slot(atlas, columns, i)), new int[] {1});
            }
        }, atlas::close);
    }

    /**
     * @param recorded per icon, its frames one per tick; the icon is written as a still image if it turns
     *                 out not to move after all
     */
    void writeClips(List<IconSource> batch, List<Motion> motions, List<List<int[]>> recorded) {
        writeBatch(() -> {
            for (int i = 0; i < batch.size(); i++) {
                Clip clip = AnimationClip.assemble(recorded.get(i), motions.get(i));
                writeIcon(batch.get(i).iconKey(), clip.frames(), clip.delays());
                if (clip.animated() && !AnimationClip.blank(clip.frames())) {
                    animated.incrementAndGet();
                }
            }
        }, () -> { });
    }

    private void writeIcon(String iconKey, List<int[]> frames, int[] delays) throws IOException {
        if (AnimationClip.blank(frames)) {
            blank.incrementAndGet();
            return;
        }
        entry(ContentPackFormat.iconEntry(iconKey), ApngEncoder.encode(frames, delays, ContentPackFormat.ICON_SIZE));
        written.incrementAndGet();
    }

    /**
     * Not through submit(): a batch must be released and counted even after an earlier failure.
     *
     * @param release frees what the batch holds; always runs
     */
    private void writeBatch(IoTask task, Runnable release) {
        pendingBatches.incrementAndGet();
        io.execute(() -> {
            try {
                if (failure == null) {
                    task.run();
                }
            } catch (Throwable e) {
                failure = e;
            } finally {
                release.run();
                pendingBatches.decrementAndGet();
            }
        });
    }

    void writeFile(String name, byte[] content) {
        submit(() -> entry(name, content));
    }

    /** Produces the content on the writer thread; an empty result writes nothing. */
    void writeFile(String name, Callable<byte[]> content) {
        submit(() -> {
            byte[] bytes = content.call();
            if (bytes != null && bytes.length > 0) {
                entry(name, bytes);
            }
        });
    }

    /** Completes the zip and moves it to {@code target}. */
    CompletableFuture<Path> finish(Path target) {
        CompletableFuture<Path> result = new CompletableFuture<>();
        io.execute(() -> {
            try {
                if (failure != null) {
                    throw failure;
                }
                zip.close();
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                result.complete(target);
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    private void submit(IoTask task) {
        io.execute(() -> {
            if (failure != null) {
                return;
            }
            try {
                task.run();
            } catch (Throwable e) {
                failure = e;
            }
        });
    }

    private void entry(String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    /** Stops the writer; a pack that was not finished is deleted. */
    @Override
    public void close() {
        io.execute(() -> {
            try {
                // Closing an already finished zip is harmless.
                zip.close();
            } catch (IOException ignored) {
                // Nothing useful to do with a failure to close a pack being thrown away.
            }
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Left behind as *.zip.part; the server never reads those.
            }
        });
        io.shutdown();
    }

    @FunctionalInterface
    private interface IoTask {
        void run() throws Exception;
    }
}
