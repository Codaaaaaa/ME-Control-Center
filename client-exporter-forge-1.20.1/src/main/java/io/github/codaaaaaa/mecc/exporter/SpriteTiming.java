package io.github.codaaaaaa.mecc.exporter;

import java.util.Arrays;

/**
 * The timeline of an animated texture, as its {@code .mcmeta} defines it. Pure Java.
 *
 * @param indices      frame numbers in playing order (a frame may appear several times)
 * @param durations    ticks each entry shows, at least 1
 * @param interpolated whether each frame blends into the next one tick by tick
 */
record SpriteTiming(int[] indices, int[] durations, boolean interpolated) {
    /**
     * What a sprite shows at one tick: {@code frame} blended with {@code next}, where {@code weight} is the
     * share of {@code frame}. A plain animation always has weight 1.
     */
    record State(int frame, int next, double weight) {
    }

    SpriteTiming {
        if (indices.length == 0 || indices.length != durations.length) {
            throw new IllegalArgumentException("one duration per frame, at least one frame");
        }
        if (Arrays.stream(durations).anyMatch(duration -> duration < 1)) {
            throw new IllegalArgumentException("durations are at least one tick");
        }
    }

    int period() {
        return Arrays.stream(durations).sum();
    }

    /** Ticks within one period at which a new entry starts, from 0. */
    int[] changes() {
        int[] changes = new int[durations.length];
        for (int i = 1; i < durations.length; i++) {
            changes[i] = changes[i - 1] + durations[i - 1];
        }
        return changes;
    }

    /**
     * The state {@code tick} ticks after the animation started from its first frame. Interpolation follows
     * the game: the weight of the current frame falls from 1 by {@code 1 / duration} each tick.
     */
    State stateAt(int tick) {
        int time = Math.floorMod(tick, period());
        int entry = 0;
        while (time >= durations[entry]) {
            time -= durations[entry];
            entry++;
        }
        int next = indices[(entry + 1) % indices.length];
        return interpolated
                ? new State(indices[entry], next, 1.0 - time / (double) durations[entry])
                : new State(indices[entry], indices[entry], 1.0);
    }
}
