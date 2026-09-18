package io.github.codaaaaaa.mecc.core.resources;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable storage snapshot of one ME network (spec section 33, {@code ResourceIndexSnapshot}).
 * Stored column-wise to stay compact for networks with tens of thousands of entries.
 */
public final class ResourceIndex {
    /** Marker in {@link #craftingAmount(int)} for "not being crafted". */
    public static final long NOT_CRAFTING = -1;

    private final Instant capturedAt;
    private final ResourceDescriptor[] descriptors;
    private final long[] amounts;
    private final boolean[] craftable;
    private final long[] craftingAmounts;

    /** Arrays are taken over, not copied: callers must not modify them afterwards. */
    public ResourceIndex(Instant capturedAt, ResourceDescriptor[] descriptors, long[] amounts, boolean[] craftable,
                         long[] craftingAmounts) {
        this.capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
        int size = descriptors.length;
        if (amounts.length != size || craftable.length != size || craftingAmounts.length != size) {
            throw new IllegalArgumentException("Resource index columns differ in length");
        }
        this.descriptors = descriptors;
        this.amounts = amounts;
        this.craftable = craftable;
        this.craftingAmounts = craftingAmounts;
    }

    public static ResourceIndex empty(Instant capturedAt) {
        return new ResourceIndex(capturedAt, new ResourceDescriptor[0], new long[0], new boolean[0], new long[0]);
    }

    public Instant capturedAt() {
        return capturedAt;
    }

    public int size() {
        return descriptors.length;
    }

    public ResourceDescriptor descriptor(int index) {
        return descriptors[index];
    }

    public long amount(int index) {
        return amounts[index];
    }

    public boolean craftable(int index) {
        return craftable[index];
    }

    /** Amount currently requested by crafting jobs, or {@link #NOT_CRAFTING}. */
    public long craftingAmount(int index) {
        return craftingAmounts[index];
    }
}
