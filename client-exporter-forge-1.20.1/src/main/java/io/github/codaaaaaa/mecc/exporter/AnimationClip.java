package io.github.codaaaaaa.mecc.exporter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Turns frames recorded one per game tick into a clip that loops without a visible jump. Pure Java.
 *
 * <p>An animation whose period is known is recorded for exactly one period and loops perfectly. One that
 * cannot be (the enchantment glint takes 82 seconds to repeat, custom renderers have no known period) is
 * recorded a little longer, and the extra frames are blended into the first ones, so the end flows into the
 * start the way the game itself would continue.
 */
final class AnimationClip {
    /** @param delays game ticks each frame shows */
    record Clip(List<int[]> frames, int[] delays) {
        boolean animated() {
            return frames.size() > 1;
        }
    }

    /**
     * How to record one icon.
     *
     * @param loopTicks      length of the clip
     * @param crossfadeTicks extra ticks recorded after the loop and blended into its start; 0 for an exact loop
     * @param changeTicks    sorted ticks, starting with 0, at which the picture can change, so only those need
     *                       drawing; {@code null} when it may change on any tick
     */
    record Motion(int loopTicks, int crossfadeTicks, int[] changeTicks) {
        Motion {
            if (loopTicks < 2 || crossfadeTicks < 0 || crossfadeTicks > loopTicks) {
                throw new IllegalArgumentException("loop " + loopTicks + ", crossfade " + crossfadeTicks);
            }
            if (changeTicks != null && (changeTicks.length == 0 || changeTicks[0] != 0)) {
                throw new IllegalArgumentException("a schedule starts at tick 0");
            }
        }

        /** Drawn on every tick. */
        Motion(int loopTicks, int crossfadeTicks) {
            this(loopTicks, crossfadeTicks, null);
        }

        int recordTicks() {
            return loopTicks + crossfadeTicks;
        }

        boolean changesAt(int tick) {
            return changeTicks == null || tick >= loopTicks || Arrays.binarySearch(changeTicks, tick) >= 0;
        }
    }

    private AnimationClip() {
    }

    /** @param recorded at least {@link Motion#recordTicks()} frames, one per tick */
    static Clip assemble(List<int[]> recorded, Motion motion) {
        int loop = motion.loopTicks();
        int crossfade = motion.crossfadeTicks();
        List<int[]> frames = new ArrayList<>(loop);
        List<Integer> delays = new ArrayList<>(loop);
        for (int i = 0; i < loop; i++) {
            int[] frame = recorded.get(i);
            if (i < crossfade) {
                // Starts almost entirely as what followed the last frame, ends as the recording itself.
                frame = mix(recorded.get(loop + i), frame, (i + 1) / (double) (crossfade + 1));
            }
            int last = frames.size() - 1;
            if (last >= 0 && Arrays.equals(frames.get(last), frame)) {
                // Slow animations change every few ticks; a longer delay is much smaller than repeated frames.
                delays.set(last, delays.get(last) + 1);
            } else {
                frames.add(frame);
                delays.add(1);
            }
        }
        return new Clip(frames, delays.stream().mapToInt(Integer::intValue).toArray());
    }

    static boolean blank(List<int[]> frames) {
        for (int[] frame : frames) {
            for (int pixel : frame) {
                if ((pixel >>> 24) != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Blends with premultiplied alpha, so a pixel fading in or out does not turn dark at its edges.
     *
     * @param t weight of {@code to}, 0 to 1
     */
    static int[] mix(int[] from, int[] to, double t) {
        int[] result = new int[from.length];
        for (int i = 0; i < from.length; i++) {
            int a = from[i];
            int b = to[i];
            double alphaA = (a >>> 24) / 255.0 * (1 - t);
            double alphaB = (b >>> 24) / 255.0 * t;
            double alpha = alphaA + alphaB;
            if (alpha <= 0) {
                continue;
            }
            int pixel = (int) Math.round(alpha * 255) << 24;
            for (int shift = 0; shift < 24; shift += 8) {
                double channel = (((a >> shift) & 0xFF) * alphaA + ((b >> shift) & 0xFF) * alphaB) / alpha;
                pixel |= Math.min(255, (int) Math.round(channel)) << shift;
            }
            result[i] = pixel;
        }
        return result;
    }
}
