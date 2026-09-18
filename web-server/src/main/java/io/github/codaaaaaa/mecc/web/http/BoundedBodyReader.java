package io.github.codaaaaaa.mecc.web.http;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import org.eclipse.jetty.io.Content;

/**
 * Reads a request body asynchronously with a hard size limit that also applies to chunked bodies without
 * a Content-Length. Reading stops, and the source is failed, as soon as the limit is exceeded.
 */
public final class BoundedBodyReader {
    private BoundedBodyReader() {
    }

    /** Thrown (as the future's failure) when the body exceeds the limit. */
    public static final class BodyTooLargeException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BodyTooLargeException(int maxBytes) {
            super("Request body larger than " + maxBytes + " bytes");
        }
    }

    public static CompletableFuture<byte[]> read(Content.Source source, int maxBytes) {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        new Reader(source, maxBytes, result).run();
        return result;
    }

    private static final class Reader implements Runnable {
        private final Content.Source source;
        private final int maxBytes;
        private final CompletableFuture<byte[]> result;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        private Reader(Content.Source source, int maxBytes, CompletableFuture<byte[]> result) {
            this.source = source;
            this.maxBytes = maxBytes;
            this.result = result;
        }

        @Override
        public void run() {
            while (true) {
                Content.Chunk chunk = source.read();
                if (chunk == null) {
                    source.demand(this);
                    return;
                }
                if (Content.Chunk.isFailure(chunk)) {
                    result.completeExceptionally(chunk.getFailure());
                    return;
                }
                try {
                    ByteBuffer buffer = chunk.getByteBuffer();
                    if (bytes.size() + buffer.remaining() > maxBytes) {
                        BodyTooLargeException tooLarge = new BodyTooLargeException(maxBytes);
                        source.fail(tooLarge);
                        result.completeExceptionally(tooLarge);
                        return;
                    }
                    byte[] part = new byte[buffer.remaining()];
                    buffer.get(part);
                    bytes.write(part, 0, part.length);
                    if (chunk.isLast()) {
                        result.complete(bytes.toByteArray());
                        return;
                    }
                } finally {
                    chunk.release();
                }
            }
        }
    }
}
