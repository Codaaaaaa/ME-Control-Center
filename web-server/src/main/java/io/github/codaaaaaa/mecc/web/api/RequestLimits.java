package io.github.codaaaaaa.mecc.web.api;

import io.github.codaaaaaa.mecc.core.security.RateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * Request budgets (spec section 36), shared by the REST API and the WebSocket endpoint: every request and
 * WebSocket upgrade counts against its client address, every state-changing request also against its player.
 */
public final class RequestLimits {
    private static final int MAX_TRACKED = 10_000;

    private final RateLimiter perAddress;
    private final RateLimiter writesPerPlayer;

    public RequestLimits(ApiSecurity security, Clock clock) {
        this.perAddress = new RateLimiter(security.requestsPerMinute(), Duration.ofMinutes(1), MAX_TRACKED, clock);
        this.writesPerPlayer = new RateLimiter(security.writesPerMinute(), Duration.ofMinutes(1), MAX_TRACKED, clock);
    }

    public boolean allowRequest(String address) {
        return perAddress.tryAcquire(address);
    }

    public boolean allowWrite(UUID player) {
        return writesPerPlayer.tryAcquire(player.toString());
    }
}
