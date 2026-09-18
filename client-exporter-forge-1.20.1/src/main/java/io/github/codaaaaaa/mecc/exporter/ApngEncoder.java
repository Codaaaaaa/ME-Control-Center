package io.github.codaaaaaa.mecc.exporter;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Encodes square RGBA frames as a PNG, or as an animated PNG (APNG) when there is more than one frame.
 * Browsers play APNG in a plain {@code <img>}; anything that only understands PNG shows the first frame.
 *
 * <p>After the first frame, each frame stores only the rectangle that differs from the one before and
 * leaves the rest of the canvas as it was. The picture is unchanged, and an icon where only a machine's
 * screen moves costs a fraction of storing every frame whole.
 *
 * <p>Pixels are in {@code NativeImage} order: {@code 0xAABBGGRR}. Pure Java, no game classes.
 */
final class ApngEncoder {
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final int MILLIS_PER_TICK = 50;

    private ApngEncoder() {
    }

    /**
     * @param frames     at least one frame, each {@code size * size} pixels
     * @param delayTicks how long each frame shows, in game ticks; ignored for a single frame
     */
    static byte[] encode(List<int[]> frames, int[] delayTicks, int size) throws IOException {
        if (frames.isEmpty() || frames.size() != delayTicks.length) {
            throw new IllegalArgumentException("one delay per frame, at least one frame");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(8192);
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(SIGNATURE);

        ByteArrayOutputStream header = new ByteArrayOutputStream();
        DataOutputStream ihdr = new DataOutputStream(header);
        ihdr.writeInt(size);
        ihdr.writeInt(size);
        ihdr.writeByte(8); // bit depth
        ihdr.writeByte(6); // RGBA
        ihdr.writeByte(0); // deflate
        ihdr.writeByte(0); // adaptive filtering
        ihdr.writeByte(0); // no interlace
        chunk(out, "IHDR", header.toByteArray());

        boolean animated = frames.size() > 1;
        if (animated) {
            ByteArrayOutputStream control = new ByteArrayOutputStream();
            DataOutputStream actl = new DataOutputStream(control);
            actl.writeInt(frames.size());
            actl.writeInt(0); // loop forever
            chunk(out, "acTL", control.toByteArray());
        }

        int sequence = 0;
        for (int i = 0; i < frames.size(); i++) {
            // {x, y, width, height} of what changed; the whole canvas for the first frame.
            int[] area = i == 0 ? new int[] {0, 0, size, size} : changedArea(frames.get(i - 1), frames.get(i), size);
            byte[] data = compress(frames.get(i), size, area);
            if (animated) {
                ByteArrayOutputStream control = new ByteArrayOutputStream();
                DataOutputStream fctl = new DataOutputStream(control);
                fctl.writeInt(sequence++);
                fctl.writeInt(area[2]);
                fctl.writeInt(area[3]);
                fctl.writeInt(area[0]);
                fctl.writeInt(area[1]);
                fctl.writeShort(delayTicks[i] * MILLIS_PER_TICK);
                fctl.writeShort(1000);
                fctl.writeByte(0); // dispose: none, so the next frame draws over this one
                fctl.writeByte(0); // blend: source, so transparent pixels replace what was there
                chunk(out, "fcTL", control.toByteArray());
            }
            if (i == 0) {
                chunk(out, "IDAT", data);
            } else {
                ByteArrayOutputStream frame = new ByteArrayOutputStream(data.length + 4);
                new DataOutputStream(frame).writeInt(sequence++);
                frame.write(data);
                chunk(out, "fdAT", frame.toByteArray());
            }
        }
        chunk(out, "IEND", new byte[0]);
        return bytes.toByteArray();
    }

    private static void chunk(DataOutputStream out, String type, byte[] data) throws IOException {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        out.writeInt(data.length);
        out.write(name);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        out.writeInt((int) crc.getValue());
    }

    /** Smallest {x, y, width, height} covering every pixel that differs; at least one pixel. */
    static int[] changedArea(int[] before, int[] after, int size) {
        int left = size;
        int top = size;
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (before[y * size + x] != after[y * size + x]) {
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        return right < 0 ? new int[] {0, 0, 1, 1} : new int[] {left, top, right - left + 1, bottom - top + 1};
    }

    /** Filtered scanlines of one area, choosing per row the filter with the smallest residuals, then deflated. */
    private static byte[] compress(int[] pixels, int size, int[] area) {
        int width = area[2];
        int height = area[3];
        int stride = width * 4;
        byte[] previous = new byte[stride];
        byte[] current = new byte[stride];
        byte[] filtered = new byte[(stride + 1) * height];
        byte[][] candidates = new byte[5][stride];
        int offset = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = pixels[(area[1] + y) * size + area[0] + x];
                current[x * 4] = (byte) pixel;
                current[x * 4 + 1] = (byte) (pixel >> 8);
                current[x * 4 + 2] = (byte) (pixel >> 16);
                current[x * 4 + 3] = (byte) (pixel >>> 24);
            }
            int best = 0;
            long bestScore = Long.MAX_VALUE;
            for (int filter = 0; filter < 5; filter++) {
                long score = 0;
                for (int i = 0; i < stride; i++) {
                    int a = i >= 4 ? current[i - 4] & 0xFF : 0;
                    int b = previous[i] & 0xFF;
                    int c = i >= 4 ? previous[i - 4] & 0xFF : 0;
                    int raw = current[i] & 0xFF;
                    int predicted = switch (filter) {
                        case 1 -> a;
                        case 2 -> b;
                        case 3 -> (a + b) >>> 1;
                        case 4 -> paeth(a, b, c);
                        default -> 0;
                    };
                    byte value = (byte) (raw - predicted);
                    candidates[filter][i] = value;
                    score += Math.abs(value);
                }
                if (score < bestScore) {
                    bestScore = score;
                    best = filter;
                }
            }
            filtered[offset++] = (byte) best;
            System.arraycopy(candidates[best], 0, filtered, offset, stride);
            offset += stride;
            byte[] swap = previous;
            previous = current;
            current = swap;
        }

        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(filtered);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(filtered.length / 4);
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a);
        int pb = Math.abs(p - b);
        int pc = Math.abs(p - c);
        return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
    }
}
