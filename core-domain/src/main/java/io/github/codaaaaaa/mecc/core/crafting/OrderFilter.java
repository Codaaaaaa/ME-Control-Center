package io.github.codaaaaaa.mecc.core.crafting;

import java.util.EnumSet;
import java.util.Set;

/** Tabs of the crafting order list (spec section 10). */
public enum OrderFilter {
    ACTIVE(OrderState.ACTIVE),
    COMPLETED(EnumSet.of(OrderState.COMPLETED)),
    /** Failed jobs, and jobs whose outcome could not be determined. */
    FAILED(EnumSet.of(OrderState.FAILED, OrderState.UNKNOWN)),
    CANCELLED(EnumSet.of(OrderState.CANCELLED)),
    ALL(EnumSet.allOf(OrderState.class));

    private final Set<OrderState> states;

    OrderFilter(Set<OrderState> states) {
        this.states = Set.copyOf(states);
    }

    public Set<OrderState> states() {
        return states;
    }
}
