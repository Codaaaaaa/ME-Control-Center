package io.github.codaaaaaa.mecc.core.crafting;

import java.util.EnumSet;
import java.util.Set;

/**
 * Normalized crafting order states (spec section 9.4). Plans ({@code CALCULATING}, {@code READY}) are not
 * orders yet: an order exists from the moment a submission starts. Stored by name: add values, never rename.
 */
public enum OrderState {
    /** The order is recorded and being handed to the crafting system. */
    SUBMITTING,
    /** A crafting CPU accepted the job. */
    RUNNING,
    COMPLETED,
    CANCELLED,
    /** The crafting system rejected the job, or it could not be handed over. */
    FAILED,
    /** The job ended or disappeared and the platform cannot tell how. */
    UNKNOWN;

    public static final Set<OrderState> ACTIVE = EnumSet.of(SUBMITTING, RUNNING);

    public boolean active() {
        return ACTIVE.contains(this);
    }
}
