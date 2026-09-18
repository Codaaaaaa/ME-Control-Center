package io.github.codaaaaaa.mecc.runtime.auth;

import io.github.codaaaaaa.mecc.core.users.PlayerProfile;
import io.github.codaaaaaa.mecc.platform.PlayerPlatform;
import io.github.codaaaaaa.mecc.platform.thread.ServerThreadGateway;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides whether a player is a server admin (operator level at or above {@code security.admin_op_level}).
 *
 * <p>Operator lists live on the server thread, so results are cached briefly to avoid a server-thread
 * round trip per HTTP request. De-opping therefore takes effect within {@link #TTL}. When the server
 * thread cannot answer, a previous answer is reused; without one the player is treated as non-admin.
 */
public final class AdminResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(AdminResolver.class);
    static final Duration TTL = Duration.ofSeconds(15);
    private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(2);

    private final PlayerPlatform players;
    private final ServerThreadGateway gateway;
    private final int adminOpLevel;
    private final Clock clock;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(boolean admin, Instant checkedAt) {
    }

    public AdminResolver(PlayerPlatform players, ServerThreadGateway gateway, int adminOpLevel, Clock clock) {
        this.players = players;
        this.gateway = gateway;
        this.adminOpLevel = adminOpLevel;
        this.clock = clock;
    }

    public int adminOpLevel() {
        return adminOpLevel;
    }

    public CompletableFuture<Boolean> isAdmin(PlayerProfile player) {
        Instant now = clock.instant();
        Cached cached = cache.get(player.uuid());
        if (cached != null && Duration.between(cached.checkedAt(), now).compareTo(TTL) < 0) {
            return CompletableFuture.completedFuture(cached.admin());
        }
        return gateway.call("players.permissionLevel", () -> players.permissionLevel(player), LOOKUP_TIMEOUT)
                .handle((level, error) -> {
                    if (error != null) {
                        LOGGER.debug("Could not check operator level of {}: {}", player.name(), error.toString());
                        return cached != null && cached.admin();
                    }
                    boolean admin = level >= adminOpLevel;
                    cache.put(player.uuid(), new Cached(admin, clock.instant()));
                    return admin;
                });
    }
}
