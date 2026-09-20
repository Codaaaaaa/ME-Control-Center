package io.github.codaaaaaa.mecc.runtime.machines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.codaaaaaa.mecc.core.machines.MachineService.MachineStatus;
import io.github.codaaaaaa.mecc.core.machines.MachineService.StuckReason;
import io.github.codaaaaaa.mecc.platform.PatternPlatform.MachineState;
import io.github.codaaaaaa.mecc.runtime.machines.DefaultMachineService.Observation;
import io.github.codaaaaaa.mecc.runtime.machines.DefaultMachineService.Track;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MachineObserveTest {
    private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");

    private static MachineState machine(boolean awaited, int pending, boolean contents, long hash, String reported) {
        return new MachineState("m1", null, null, List.of("p1"), awaited, pending, contents, hash, reported, null,
                null);
    }

    /** Seen that long ago, and expected to be working for just as long. */
    private static Track seen(long hash, String reported, int secondsAgo) {
        return new Track(hash, null, reported, NOW.minusSeconds(secondsAgo), null, null, NOW.minusSeconds(secondsAgo));
    }

    /** Seen that long ago, but nothing has been expected of it since. */
    private static Track resting(long hash, String reported, int secondsAgo) {
        return new Track(hash, null, reported, NOW.minusSeconds(secondsAgo), null, null, null);
    }

    // --- machines with nothing to report: the guess ------------------------------------------------

    @Test
    void anAwaitedMachineThatHoldsInputsAndDoesNotChangeGetsStuck() {
        MachineState state = machine(true, 0, true, 7, null);
        assertEquals(MachineStatus.WORKING, DefaultMachineService.observe(state, null, NOW).status(), "first sighting");
        assertEquals(MachineStatus.WAITING, DefaultMachineService.observe(state, seen(7, null, 60), NOW).status());
        Observation stuck = DefaultMachineService.observe(state, seen(7, null, 121), NOW);
        assertEquals(MachineStatus.STUCK, stuck.status());
        assertEquals(StuckReason.NO_CHANGE, stuck.reason());
        assertEquals(NOW.minusSeconds(121), stuck.stuckSince());
        assertEquals(MachineStatus.WORKING, DefaultMachineService.observe(machine(true, 0, true, 8, null),
                seen(7, null, 300), NOW).status(), "its contents changed");
    }

    @Test
    void nothingIsExpectedOfAMachineNoJobWaitsFor() {
        Observation idle = DefaultMachineService.observe(machine(false, 0, true, 7, null), seen(7, null, 600), NOW);
        assertEquals(MachineStatus.IDLE, idle.status(), "leftovers in an idle machine are not a problem");
        assertNull(idle.stuckSince());
        assertEquals(MachineStatus.IDLE, DefaultMachineService.observe(machine(true, 0, false, 0, null),
                seen(0, null, 600), NOW).status(), "awaited but empty: nothing to work on");
    }

    @Test
    void aStuckMachineStaysStuckWhileNothingAboutItChanges() {
        Track wasStuck = new Track(7, null, null, NOW.minusSeconds(900), MachineStatus.STUCK, StuckReason.NO_CHANGE,
                NOW.minusSeconds(900));
        // The CPU is between batches, so it waits for nothing right now - the machine has still not moved.
        Observation still = DefaultMachineService.observe(machine(false, 0, true, 7, null), wasStuck, NOW);
        assertEquals(MachineStatus.STUCK, still.status());
        assertEquals(StuckReason.NO_CHANGE, still.reason());
        assertEquals(NOW.minusSeconds(900), still.expectedSince(), "the clock keeps running across the gap");
        assertEquals(MachineStatus.WORKING, DefaultMachineService.observe(machine(false, 0, true, 8, null), wasStuck,
                NOW).status(), "it moved: the flag is gone");
        assertEquals(MachineStatus.IDLE, DefaultMachineService.observe(machine(false, 0, false, 0, null),
                new Track(0, null, null, NOW.minusSeconds(900), MachineStatus.STUCK, StuckReason.NO_CHANGE, null), NOW)
                .status(), "nothing left in it");
    }

    @Test
    void theStuckClockStartsWhenSomethingIsFirstExpected() {
        // It sat there for an hour with nothing expected of it; a plan that now waits for it gives it its two minutes.
        MachineState state = machine(true, 0, true, 7, null);
        Observation fresh = DefaultMachineService.observe(state, resting(7, null, 3600), NOW);
        assertEquals(MachineStatus.WORKING, fresh.status());
        assertEquals(NOW, fresh.expectedSince());
        assertEquals(NOW.minusSeconds(3600), fresh.lastChange(), "when it last moved is still reported as it is");
        assertEquals(MachineStatus.STUCK, DefaultMachineService.observe(state,
                new Track(7, null, null, NOW.minusSeconds(3600), MachineStatus.WORKING, null, NOW.minusSeconds(121)),
                NOW).status(), "expected and unmoved since then");
    }

    @Test
    void aProviderThatCannotHandOverItsItemsIsBlocked() {
        assertEquals(StuckReason.OUTPUT_BLOCKED, DefaultMachineService.observe(machine(false, 2, false, 0, null),
                seen(0, null, 200), NOW).reason());
    }

    // --- machines that report their own state: taken at their word ---------------------------------

    @Test
    void aMachineThatKnowsItsOwnStateIsJudgedByItAlone() {
        assertEquals(MachineStatus.WORKING, DefaultMachineService.observe(machine(true, 0, false, 0, "WORKING"),
                seen(0, "WORKING", 600), NOW).status());
        Observation waiting = DefaultMachineService.observe(machine(false, 0, false, 0, "WAITING"),
                seen(0, "WAITING", 600), NOW);
        assertEquals(MachineStatus.STUCK, waiting.status(), "no crafting job has to wait for it to be stuck");
        assertEquals(StuckReason.MACHINE_WAITING, waiting.reason());
        assertEquals(MachineStatus.WAITING, DefaultMachineService.observe(machine(false, 0, false, 0, "WAITING"),
                seen(0, "WAITING", 60), NOW).status());
        assertEquals(MachineStatus.WORKING, DefaultMachineService.observe(machine(false, 0, false, 0, "WAITING"),
                seen(0, "IDLE", 600), NOW).status(), "it only just said so: a moment without power is not a fault");
    }

    @Test
    void aMachineWithNothingToMakeIsIdleHoweverLongItSaysSo() {
        // GregTech's IDLE is "no recipe matched", not "cannot run": the one mistake behind whole walls of false
        // stuck reports, since an idle machine never changes and so never cleared the flag again.
        for (Track previous : List.of(seen(0, "IDLE", 3600), resting(0, "IDLE", 3600),
                new Track(0, null, "IDLE", NOW.minusSeconds(3600), MachineStatus.STUCK, StuckReason.MACHINE_WAITING,
                        NOW.minusSeconds(3600)))) {
            Observation idle = DefaultMachineService.observe(machine(true, 0, false, 0, "IDLE"), previous, NOW);
            assertEquals(MachineStatus.IDLE, idle.status());
            assertNull(idle.reason());
            assertNull(idle.stuckSince());
        }
    }

    @Test
    void aMachineSwitchedOffIsNotStuck() {
        Observation off = DefaultMachineService.observe(machine(true, 0, true, 7, "SUSPEND"),
                seen(7, "SUSPEND", 3600), NOW);
        assertEquals(MachineStatus.DISABLED, off.status());
        assertNull(off.stuckSince(), "nothing to announce: someone turned it off on purpose");
    }

    @Test
    void aReportingMachineItsProviderCannotReachIsStillBlocked() {
        Observation blocked = DefaultMachineService.observe(machine(false, 3, false, 0, "IDLE"),
                seen(0, "IDLE", 600), NOW);
        assertEquals(MachineStatus.STUCK, blocked.status());
        assertEquals(StuckReason.OUTPUT_BLOCKED, blocked.reason(), "the provider is stuck even if the machine is fine");
    }
}
