package io.github.codaaaaaa.mecc.core.auth;

/**
 * Transport-level facts about the caller.
 *
 * @param address   client IP after trusted-proxy resolution
 * @param userAgent User-Agent header, may be {@code null}
 */
public record ClientInfo(String address, String userAgent) {
    public static final int MAX_USER_AGENT_LENGTH = 256;

    public ClientInfo {
        if (userAgent != null && userAgent.length() > MAX_USER_AGENT_LENGTH) {
            userAgent = userAgent.substring(0, MAX_USER_AGENT_LENGTH);
        }
    }
}
