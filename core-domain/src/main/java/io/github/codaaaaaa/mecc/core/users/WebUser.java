package io.github.codaaaaaa.mecc.core.users;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An ME Control Center user. Identity is the Minecraft player UUID; a record exists once the player pairs a
 * browser or is added to a network.
 *
 * @param playerUuid Minecraft profile UUID
 * @param playerName last known player name (display only; names can change)
 * @param createdAt  when ME Control Center first saw the player
 * @param lastSeenAt last successful authentication, or {@code null} if the player never paired
 */
public record WebUser(UUID playerUuid, String playerName, Instant createdAt, Instant lastSeenAt) {
    public WebUser {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
