package io.github.codaaaaaa.mecc.core.networks;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A persisted identity anchor of an ME Control Center network: on Minecraft 1.20.1, a Wireless Access Point.
 *
 * @param location   where the anchor block is
 * @param ownerUuid  owning player of the anchor's grid node, or {@code null} if it has none
 * @param createdAt  when the anchor was attached to the network record
 */
public record NetworkAnchor(BlockLocation location, UUID ownerUuid, Instant createdAt) {
    public NetworkAnchor {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
