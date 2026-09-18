package io.github.codaaaaaa.mecc.core.alerts;

/** Recovery state of a condition rule. Event rules stay {@link #OK}. */
public enum AlertState {
    /** The condition does not hold. */
    OK,
    /** The condition holds and its owner was notified. */
    FIRING,
    /** The condition holds, but it started within the cooldown, so nobody was notified yet. */
    SUPPRESSED
}
