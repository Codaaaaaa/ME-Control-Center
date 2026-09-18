package io.github.codaaaaaa.mecc.core.crafting;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SavedOrderRepository {

    void insert(SavedOrder order);

    /** Replaces name, amount, CPU, notes, and update time. */
    boolean update(SavedOrder order);

    Optional<SavedOrder> find(UUID id);

    /** A player's presets on one network, by name. */
    List<SavedOrder> list(UUID playerUuid, UUID networkId);

    int countByPlayer(UUID playerUuid);

    boolean delete(UUID id);

    /** Deletes a player's presets on one network. Returns the number deleted. */
    int deleteAll(UUID playerUuid, UUID networkId);
}
