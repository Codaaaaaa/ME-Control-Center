package io.github.codaaaaaa.mecc.core.networks;

import io.github.codaaaaaa.mecc.core.permissions.NetworkRole;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * @param addedBy player who granted the role, or {@code null} for the enrolling owner
 */
public record NetworkMember(UUID networkId, UUID playerUuid, NetworkRole role, UUID addedBy, Instant addedAt) {
    public NetworkMember {
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(addedAt, "addedAt");
    }
}
