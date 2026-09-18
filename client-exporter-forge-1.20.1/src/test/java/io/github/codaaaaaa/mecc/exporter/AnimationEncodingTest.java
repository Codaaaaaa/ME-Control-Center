package io.github.codaaaaaa.mecc.exporter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.codaaaaaa.mecc.exporter.AnimationClip.Clip;
import io.github.codaaaaaa.mecc.exporter.AnimationClip.Motion;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class AnimationEncodingTest {
    private static final int SIZE = 4;
    /** NativeImage order, 0xAABBGGRR. */
    private static final int RED = 0xFF0000FF;
    private static final int BLUE = 0xFFFF0000;

    @Test
    void writesAnApngThatPlainPngReadersShowAsItsFirstFrame() throws Exception {
        byte[] png = ApngEncoder.encode(List.of(solid(RED), solid(BLUE), solid(RED)), new int[] {1, 3, 2}, SIZE);

        List<String> chunks = new ArrayList<>();
        List<Integer> sequence = new ArrayList<>();
        List<Integer> delays = new ArrayList<>();
        int frames = -1;
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(png));
        in.skipNBytes(8);
        while (in.available() > 0) {
            int length = in.readInt();
            byte[] type = in.readNBytes(4);
            byte[] data = in.readNBytes(length);
            CRC32 crc = new CRC32();
            crc.update(type);
            crc.update(data);
            assertEquals((int) crc.getValue(), in.readInt(), "chunk checksum");
            String name = new String(type, StandardCharsets.US_ASCII);
            chunks.add(name);
            DataInputStream body = new DataInputStream(new ByteArrayInputStream(data));
            switch (name) {
                case "acTL" -> frames = body.readInt();
                case "fcTL" -> {
                    sequence.add(body.readInt());
                    body.skipNBytes(16);
                    delays.add((int) body.readShort());
                }
                case "fdAT" -> sequence.add(body.readInt());
                default -> { }
            }
        }

        assertEquals(3, frames);
        assertEquals(List.of("IHDR", "acTL", "fcTL", "IDAT", "fcTL", "fdAT", "fcTL", "fdAT", "IEND"), chunks);
        assertEquals(List.of(0, 1, 2, 3, 4), sequence, "fcTL and fdAT share one gapless sequence");
        assertEquals(List.of(50, 150, 100), delays, "milliseconds, from game ticks");
        BufferedImage first = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(0xFFFF0000, first.getRGB(1, 1), "red, converted from NativeImage order");
    }

    @Test
    void storesOnlyWhatChangedAfterTheFirstFrame() throws Exception {
        int[] second = solid(RED);
        second[1 * SIZE + 2] = BLUE;
        second[2 * SIZE + 3] = BLUE;
        assertArrayEquals(new int[] {2, 1, 2, 2}, ApngEncoder.changedArea(solid(RED), second, SIZE));
        assertArrayEquals(new int[] {0, 0, 1, 1}, ApngEncoder.changedArea(solid(RED), solid(RED), SIZE));

        byte[] png = ApngEncoder.encode(List.of(solid(RED), second), new int[] {1, 1}, SIZE);
        List<int[]> areas = new ArrayList<>();
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(png));
        in.skipNBytes(8);
        while (in.available() > 0) {
            int length = in.readInt();
            String name = new String(in.readNBytes(4), StandardCharsets.US_ASCII);
            DataInputStream body = new DataInputStream(new ByteArrayInputStream(in.readNBytes(length)));
            in.readInt();
            if (name.equals("fcTL")) {
                body.readInt();
                int width = body.readInt();
                int height = body.readInt();
                areas.add(new int[] {body.readInt(), body.readInt(), width, height});
            }
        }
        assertArrayEquals(new int[] {0, 0, SIZE, SIZE}, areas.get(0), "the first frame covers the canvas");
        assertArrayEquals(new int[] {2, 1, 2, 2}, areas.get(1), "the second only the changed pixels");
    }

    @Test
    void writesASinglePlainPngForOneFrame() throws Exception {
        byte[] png = ApngEncoder.encode(List.of(solid(BLUE)), new int[] {1}, SIZE);
        assertFalse(new String(png, StandardCharsets.ISO_8859_1).contains("acTL"));
        assertEquals(0xFF0000FF, ImageIO.read(new ByteArrayInputStream(png)).getRGB(0, 0));
    }

    @Test
    void mergesRepeatedFramesIntoLongerDelays() {
        // A texture that changes every third tick, recorded for exactly one period.
        List<int[]> recorded = List.of(solid(RED), solid(RED), solid(RED), solid(BLUE), solid(BLUE), solid(BLUE));
        Clip clip = AnimationClip.assemble(recorded, new Motion(6, 0));
        assertEquals(2, clip.frames().size());
        assertArrayEquals(new int[] {3, 3}, clip.delays());
    }

    @Test
    void crossFadesTheExtraFramesIntoTheStart() {
        List<int[]> recorded = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            recorded.add(solid(i < 4 ? RED : BLUE));
        }
        Clip clip = AnimationClip.assemble(recorded, new Motion(4, 2));
        // Frame 0 is mostly what followed the loop (blue), frame 1 mostly the recording (red), then red.
        int first = clip.frames().get(0)[0];
        int second = clip.frames().get(1)[0];
        assertTrue(((first >> 16) & 0xFF) > (first & 0xFF), "first frame leans blue");
        assertTrue((second & 0xFF) > ((second >> 16) & 0xFF), "second frame leans red");
        assertEquals(RED, clip.frames().get(2)[0]);
        assertEquals(4, Arrays.stream(clip.delays()).sum(), "the clip is exactly one loop long");
    }

    @Test
    void blendsWithoutDarkeningTransparentEdges() {
        int[] mixed = AnimationClip.mix(solid(0), solid(RED), 0.5);
        assertEquals(0xFF, mixed[0] & 0xFF, "colour of the only visible pixel is kept");
        assertEquals(0x80, mixed[0] >>> 24);
        assertTrue(AnimationClip.blank(List.of(solid(0), solid(0x00FFFFFF))));
    }

    private static int[] solid(int pixel) {
        int[] pixels = new int[SIZE * SIZE];
        Arrays.fill(pixels, pixel);
        return pixels;
    }
}
