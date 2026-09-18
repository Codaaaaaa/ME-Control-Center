package io.github.codaaaaaa.mecc.core.crafting;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One entry of an order's history.
 *
 * @param actorUuid player who caused it, or {@code null} for ME Control Center itself or the game
 */
public record OrderEvent(UUID orderId, Instant at, OrderEventType type, UUID actorUuid, Map<String, String> details) {
    public OrderEvent {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(type, "type");
        details = Map.copyOf(details);
    }
}
