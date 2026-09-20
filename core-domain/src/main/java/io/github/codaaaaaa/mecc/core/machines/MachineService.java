package io.github.codaaaaaa.mecc.core.machines;

import io.github.codaaaaaa.mecc.core.auth.Session;
import io.github.codaaaaaa.mecc.core.networks.BlockLocation;
import io.github.codaaaaaa.mecc.core.resources.ResourceViews.ResourceLabel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * The machines a network's pattern providers and pattern buffers supply, and whether they are working. Requires
 * {@code VIEW_NETWORK}.
 */
public interface MachineService {

    CompletionStage<MachineList> machines(Session session, UUID networkId, String locale);

    enum MachineStatus {
        /** It says it is switched off (GregTech {@code SUSPEND}): a player or a cover stopped it. */
        DISABLED,
        /** Nothing is expected of it and nothing is happening. */
        IDLE,
        /** It is running, or its contents changed recently. */
        WORKING,
        /** A crafting job waits for it, but it has not changed for a little while. */
        WAITING,
        /** A crafting job waits for it and it has not changed for {@code stuckAfterSeconds}. */
        STUCK
    }

    /** Why a machine is waiting or stuck. */
    enum StuckReason {
        /** Its provider holds items the machine does not accept (full, or the wrong input). */
        OUTPUT_BLOCKED,
        /** The machine itself reports it cannot run; {@code waitingReason} says why, in its own words. */
        MACHINE_WAITING,
        /** It holds the inputs but nothing changes. */
        NO_CHANGE
    }

    /**
     * @param block      the machine as an item, or {@code null}
     * @param reported   the machine's own status when it has one (e.g. GregTech {@code WORKING}), else {@code null}
     * @param progress   progress of its current operation 0-1 when it reports one, else {@code null}
     * @param awaited    a crafting CPU waits for something it makes
     * @param lastChange when it was last seen changing, or first seen
     * @param reason     for {@code WAITING} and {@code STUCK}, else {@code null}
     * @param waitingReason the machine's own wording for {@code MACHINE_WAITING}, when it gives one
     */
    record MachineView(String id, ResourceLabel block, BlockLocation location, int providers, MachineStatus status,
                       StuckReason reason, String reported, String waitingReason, Double progress, boolean awaited,
                       int pendingSends, Instant lastChange) {
    }

    /** @param stuckAfterSeconds how long an awaited machine may stay unchanged before it counts as stuck */
    record MachineList(Instant capturedAt, List<MachineView> machines, long stuckAfterSeconds, String assetVersion) {
    }
}
