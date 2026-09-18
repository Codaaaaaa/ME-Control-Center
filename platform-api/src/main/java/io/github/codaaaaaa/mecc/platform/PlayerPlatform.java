package io.github.codaaaaaa.mecc.platform;

import io.github.codaaaaaa.mecc.core.users.PlayerProfile;
import java.util.Optional;

/** Player identity and operator permissions. */
public interface PlayerPlatform {

    /**
     * Operator permission level (0-4) of a player, whether or not they are online.
     * Must not perform network lookups.
     */
    @ServerThreadOnly
    int permissionLevel(PlayerProfile player);

    /** Case-insensitive lookup of an online player. */
    @ServerThreadOnly
    Optional<PlayerProfile> findOnlinePlayer(String name);
}
