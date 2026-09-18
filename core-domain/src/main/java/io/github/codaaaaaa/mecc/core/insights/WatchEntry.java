package io.github.codaaaaaa.mecc.core.insights;

import io.github.codaaaaaa.mecc.core.crafting.OrderTarget;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One resource on a player's personal watchlist (spec section 21). The resource is frozen like an order
 * target, so the entry stays readable while the resource is out of storage or the network is offline.
 */
public record WatchEntry(UUID id, UUID playerUuid, UUID networkId, OrderTarget resource, Instant createdAt) {
    public WatchEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
