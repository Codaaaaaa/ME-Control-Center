package io.github.codaaaaaa.mecc.core.status;

/**
 * Immutable copy of basic Minecraft server state, captured on the server thread.
 *
 * @param dedicated         {@code true} for a dedicated server, {@code false} for an integrated (singleplayer/LAN) one
 * @param playersOnline     connected player count
 * @param maxPlayers        configured player limit
 * @param averageTickMillis rolling average tick duration in milliseconds
 * @param motd              server message of the day
 */
public record ServerSnapshot(
        boolean dedicated,
        int playersOnline,
        int maxPlayers,
        double averageTickMillis,
        String motd) {
}
