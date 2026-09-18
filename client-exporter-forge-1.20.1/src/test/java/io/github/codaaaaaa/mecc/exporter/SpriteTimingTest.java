package io.github.codaaaaaa.mecc.exporter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SpriteTimingTest {

    @Test
    void followsTheFrameListAndItsTimes() {
        // Frames 0, 2, 1 showing for 3, 1 and 2 ticks, like an .mcmeta frame list with times.
        SpriteTiming timing = new SpriteTiming(new int[] {0, 2, 1}, new int[] {3, 1, 2}, false);
        assertEquals(6, timing.period());
        assertArrayEquals(new int[] {0, 3, 4}, timing.changes());
        int[] shown = new int[8];
        for (int tick = 0; tick < shown.length; tick++) {
            shown[tick] = timing.stateAt(tick).frame();
        }
        assertArrayEquals(new int[] {0, 0, 0, 2, 1, 1, 0, 0}, shown, "wraps around after one period");
        assertEquals(1.0, timing.stateAt(1).weight(), "plain animations never blend");
    }

    @Test
    void interpolatesTowardsTheNextFrameAsTheGameDoes() {
        SpriteTiming timing = new SpriteTiming(new int[] {0, 1}, new int[] {4, 4}, true);
        SpriteTiming.State start = timing.stateAt(0);
        assertEquals(0, start.frame());
        assertEquals(1, start.next());
        assertEquals(1.0, start.weight());
        assertEquals(0.25, timing.stateAt(3).weight(), 1e-9);
        // The last frame blends back into the first.
        SpriteTiming.State last = timing.stateAt(6);
        assertEquals(1, last.frame());
        assertEquals(0, last.next());
        assertEquals(0.5, last.weight(), 1e-9);
    }
}
