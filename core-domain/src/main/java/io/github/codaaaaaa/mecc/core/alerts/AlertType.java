package io.github.codaaaaaa.mecc.core.alerts;

/**
 * What an alert rule watches (spec section 23). Condition rules are checked periodically and fire when their
 * condition starts to hold, then resolve when it stops; craft rules fire per crafting order.
 */
public enum AlertType {
    /** Stored amount of a resource below the threshold (raw units). */
    RESOURCE_BELOW(true, true, Threshold.AMOUNT),
    /** Stored amount of a resource above the threshold (raw units). */
    RESOURCE_ABOVE(true, true, Threshold.AMOUNT),
    /** Stored amount fell by at least the threshold percent within the rule's window. */
    RESOURCE_DROP(true, true, Threshold.PERCENT),
    /** Stored amount rose by at least the threshold percent within the rule's window. */
    RESOURCE_RISE(true, true, Threshold.PERCENT),
    /** The network is not loaded or has no power. */
    NETWORK_OFFLINE(true, false, Threshold.NONE),
    /** Stored energy below the threshold, in percent of capacity. */
    ENERGY_LOW(true, false, Threshold.PERCENT),
    /** Every crafting CPU is busy. */
    CPU_SATURATED(true, false, Threshold.NONE),
    /** One of the rule owner's crafting orders completed; optionally only for one resource. */
    CRAFT_COMPLETED(false, false, Threshold.NONE),
    /** One of the rule owner's crafting orders failed or was lost; optionally only for one resource. */
    CRAFT_FAILED(false, false, Threshold.NONE),
    /** One of the rule owner's running orders made no progress for the threshold in minutes. */
    CRAFT_STALLED(false, false, Threshold.MINUTES),
    /**
     * A machine a crafting job waits for holds its inputs (or refuses them) and has not changed for the threshold in
     * minutes; optionally only machines of one kind (the resource is the machine block).
     */
    MACHINE_STUCK(false, false, Threshold.MINUTES);

    /** What a rule's threshold means. */
    public enum Threshold {
        NONE,
        AMOUNT,
        PERCENT,
        MINUTES
    }

    private final boolean condition;
    private final boolean needsResource;
    private final Threshold threshold;

    AlertType(boolean condition, boolean needsResource, Threshold threshold) {
        this.condition = condition;
        this.needsResource = needsResource;
        this.threshold = threshold;
    }

    public boolean condition() {
        return condition;
    }

    public boolean needsResource() {
        return needsResource;
    }

    public Threshold threshold() {
        return threshold;
    }

    public boolean needsThreshold() {
        return threshold != Threshold.NONE;
    }

    /** Percentage-change rules compare against the amount one window ago. */
    public boolean needsWindow() {
        return this == RESOURCE_DROP || this == RESOURCE_RISE;
    }

    /**
     * Craft and machine rules may name a resource to narrow them down; condition rules take one only when they need
     * it.
     */
    public boolean allowsResource() {
        return needsResource || !condition;
    }
}
