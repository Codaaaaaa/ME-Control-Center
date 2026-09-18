package io.github.codaaaaaa.mecc.core.config;

import io.github.codaaaaaa.mecc.core.net.IpRange;
import java.util.ArrayList;
import java.util.List;

/**
 * Authentication and request-trust settings ({@code [security]} section).
 *
 * @param pairingKeyTtlSeconds lifetime of a {@code /mecc pair} key
 * @param trustedProxies       IPs/CIDRs whose {@code X-Forwarded-*} headers are believed; empty trusts nobody
 * @param adminOverride        whether server admins get Owner-level access to every enrolled network
 * @param adminOpLevel         minimum operator permission level that counts as server admin
 * @param requireHttpsCookie   always mark the device cookie {@code Secure}
 * @param allowedOrigins       extra browser origins allowed to send state-changing requests
 */
public record SecurityConfig(
        int pairingKeyTtlSeconds,
        List<String> trustedProxies,
        boolean adminOverride,
        int adminOpLevel,
        boolean requireHttpsCookie,
        List<String> allowedOrigins) {

    public static final int DEFAULT_PAIRING_KEY_TTL_SECONDS = 300;
    public static final int DEFAULT_ADMIN_OP_LEVEL = 4;

    public SecurityConfig {
        trustedProxies = List.copyOf(trustedProxies);
        allowedOrigins = List.copyOf(allowedOrigins);
    }

    public static SecurityConfig defaults() {
        return new SecurityConfig(DEFAULT_PAIRING_KEY_TTL_SECONDS, List.of(), true, DEFAULT_ADMIN_OP_LEVEL, false, List.of());
    }

    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (pairingKeyTtlSeconds < 30 || pairingKeyTtlSeconds > 3600) {
            problems.add("security.pairing_key_ttl_seconds must be between 30 and 3600, got " + pairingKeyTtlSeconds);
        }
        for (String proxy : trustedProxies) {
            if (IpRange.parse(proxy).isEmpty()) {
                problems.add("security.trusted_proxies contains an invalid IP address or CIDR block: " + proxy);
            }
        }
        if (adminOpLevel < 1 || adminOpLevel > 4) {
            problems.add("security.admin_op_level must be between 1 and 4, got " + adminOpLevel);
        }
        for (String origin : allowedOrigins) {
            if (WebConfig.originOf(origin) == null) {
                problems.add("security.allowed_origins contains an invalid origin: " + origin);
            }
        }
        return problems;
    }

    public List<IpRange> trustedProxyRanges() {
        return trustedProxies.stream().flatMap(proxy -> IpRange.parse(proxy).stream()).toList();
    }
}
