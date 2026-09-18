package io.github.codaaaaaa.mecc.core.users;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository {

    Optional<WebUser> find(UUID playerUuid);

    /** Case-insensitive lookup by last known name. */
    Optional<WebUser> findByName(String playerName);

    Map<UUID, WebUser> findAll(Collection<UUID> playerUuids);

    /** Creates the user or refreshes their name. Returns the stored record. */
    WebUser upsert(PlayerProfile profile, Instant now);

    void touchLastSeen(UUID playerUuid, Instant now);
}
