package io.github.codaaaaaa.mecc.core.networks;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Persistent ME Control Center network record (spec section 29). Its UUID is the public network ID; AE2 runtime
 * grid objects are never exposed or persisted.
 *
 * @param id               public, stable identifier
 * @param displayName      user-chosen name
 * @param ownerPlayerUuid  primary owner (the player who enrolled the network)
 * @param createdAt        enrollment time
 * @param lastSeenAt       last time the network was resolved to a loaded grid, or {@code null}
 * @param status           last persisted reconciliation status
 * @param anchors          identity anchors
 */
public record WebNetwork(
        UUID id,
        String displayName,
        UUID ownerPlayerUuid,
        Instant createdAt,
        Instant lastSeenAt,
        NetworkRecordStatus status,
        List<NetworkAnchor> anchors) {

    public static final int MAX_NAME_LENGTH = 48;

    public WebNetwork {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(ownerPlayerUuid, "ownerPlayerUuid");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(status, "status");
        anchors = List.copyOf(anchors);
    }

    public WebNetwork withAnchors(List<NetworkAnchor> newAnchors) {
        return new WebNetwork(id, displayName, ownerPlayerUuid, createdAt, lastSeenAt, status, newAnchors);
    }
}
