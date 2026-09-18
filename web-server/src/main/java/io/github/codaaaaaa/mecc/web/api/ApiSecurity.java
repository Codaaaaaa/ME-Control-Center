package io.github.codaaaaaa.mecc.web.api;

import io.github.codaaaaaa.mecc.core.config.SecurityConfig;
import io.github.codaaaaaa.mecc.core.net.IpRange;
import java.util.List;
import java.util.Set;

/**
 * Request-trust settings for the API.
 *
 * @param trustedProxies  peers whose forwarding headers are believed
 * @param alwaysSecureCookie mark the device cookie {@code Secure} even for plain HTTP requests
 * @param allowedOrigins  normalized origins ({@code scheme://host[:port]}) allowed in addition to the request's own host
 * @param maxBodyBytes    maximum request body size
 * @param requestsPerMinute requests (and WebSocket upgrades) one client address may make per minute
 * @param writesPerMinute state-changing requests one player may make per minute
 */
public record ApiSecurity(List<IpRange> trustedProxies, boolean alwaysSecureCookie, Set<String> allowedOrigins, int maxBodyBytes,
                          int requestsPerMinute, int writesPerMinute) {
    public static final int DEFAULT_MAX_BODY_BYTES = 64 * 1024;

    public ApiSecurity {
        trustedProxies = List.copyOf(trustedProxies);
        allowedOrigins = Set.copyOf(allowedOrigins);
    }

    public static ApiSecurity defaults() {
        return new ApiSecurity(List.of(), false, Set.of(), DEFAULT_MAX_BODY_BYTES,
                SecurityConfig.DEFAULT_REQUESTS_PER_MINUTE, SecurityConfig.DEFAULT_WRITES_PER_MINUTE);
    }
}
