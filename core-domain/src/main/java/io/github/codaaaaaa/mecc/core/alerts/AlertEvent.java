package io.github.codaaaaaa.mecc.core.alerts;

import io.github.codaaaaaa.mecc.core.crafting.OrderTarget;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Something an alert rule reported.
 *
 * @param id        assigned by the repository; ignored on append
 * @param resource  the rule's resource or the order's target, or {@code null}
 * @param value     the observed amount or energy percent, or the order amount; {@code null} when not applicable
 * @param threshold the rule's threshold at the time, or {@code null}
 * @param orderId   the crafting order, for craft events
 */
public record AlertEvent(long id, UUID ruleId, UUID playerUuid, UUID networkId, AlertType type, Kind kind, Instant at,
                         OrderTarget resource, Long value, Long threshold, UUID orderId) {

    public enum Kind {
        TRIGGERED,
        RESOLVED
    }

    public AlertEvent {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(at, "at");
    }

    public AlertEvent withId(long newId) {
        return new AlertEvent(newId, ruleId, playerUuid, networkId, type, kind, at, resource, value, threshold, orderId);
    }
}
