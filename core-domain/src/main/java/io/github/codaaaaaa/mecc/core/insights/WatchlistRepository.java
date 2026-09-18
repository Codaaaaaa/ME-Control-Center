package io.github.codaaaaaa.mecc.core.insights;

import io.github.codaaaaaa.mecc.core.resources.ResourceId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface WatchlistRepository {

    /** @throws io.github.codaaaaaa.mecc.core.persistence.DuplicateKeyException if the player already watches it */
    void insert(WatchEntry entry);

    Optional<WatchEntry> find(UUID id);

    Optional<WatchEntry> find(UUID playerUuid, UUID networkId, ResourceId resource);

    /** A player's entries on one network, oldest first. */
    List<WatchEntry> list(UUID playerUuid, UUID networkId);

    int countByPlayer(UUID playerUuid);

    boolean delete(UUID id);

    /** Deletes a player's entries on one network. Returns the number deleted. */
    int deleteAll(UUID playerUuid, UUID networkId);

    /** Every watched resource per network, once however many players watch it (spec section 21.2). */
    Map<UUID, Set<ResourceId>> watchedSeries();
}
