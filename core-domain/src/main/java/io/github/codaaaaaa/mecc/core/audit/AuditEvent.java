package io.github.codaaaaaa.mecc.core.audit;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One audited control action (spec section 35). Never put tokens or pairing keys in any field.
 *
 * @param actorPlayerUuid acting player, or {@code null} for the system/console
 * @param deviceId        acting device, or {@code null} (e.g. in-game command)
 * @param networkId       affected network, or {@code null}
 * @param target          action target, e.g. a device ID or player UUID
 * @param adminOverride   the action was only permitted through server-admin override
 * @param parameters      important parameters
 */
public record AuditEvent(
        Instant at,
        UUID actorPlayerUuid,
        String deviceId,
        UUID networkId,
        AuditAction action,
        String target,
        AuditResult result,
        boolean adminOverride,
        Map<String, String> parameters) {

    public AuditEvent {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(result, "result");
        parameters = Map.copyOf(parameters);
    }

    public enum AuditResult {
        SUCCESS,
        DENIED,
        FAILED
    }
}
