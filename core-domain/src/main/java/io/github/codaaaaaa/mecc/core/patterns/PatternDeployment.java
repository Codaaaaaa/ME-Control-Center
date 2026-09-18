package io.github.codaaaaaa.mecc.core.patterns;

import io.github.codaaaaaa.mecc.core.crafting.OrderTarget;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One encode or deploy attempt, kept as history (spec section 34, {@code pattern_deployments}).
 *
 * @param draftId      draft the pattern came from, or {@code null}
 * @param output       the primary output, frozen for history, or {@code null} when it could not be determined
 * @param providerId   destination provider for deployments, or {@code null} when encoded into ME storage
 * @param providerName provider name at the time, or {@code null}
 * @param slot         provider slot the pattern went into, or {@code null}
 * @param errorCode    {@code null} on success
 */
public record PatternDeployment(
        UUID id,
        UUID networkId,
        UUID actorUuid,
        String deviceId,
        UUID draftId,
        PatternType type,
        Action action,
        OrderTarget output,
        String providerId,
        String providerName,
        Integer slot,
        String errorCode,
        Instant at) {

    public enum Action {
        /** Encoded into ME storage. */
        ENCODE,
        /** Encoded and placed into a pattern provider. */
        DEPLOY
    }

    public PatternDeployment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(actorUuid, "actorUuid");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(at, "at");
    }

    public boolean success() {
        return errorCode == null;
    }
}
