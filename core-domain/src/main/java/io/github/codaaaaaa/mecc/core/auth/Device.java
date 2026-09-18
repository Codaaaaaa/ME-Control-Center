package io.github.codaaaaaa.mecc.core.auth;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A paired browser or API client. The token itself is never stored; see {@link SecretCodes#hashToken}.
 *
 * @param id          short public identifier
 * @param playerUuid  owning player
 * @param name        user-editable label such as "Chrome · Windows"
 * @param userAgent   User-Agent at pairing time (truncated), may be {@code null}
 * @param createdAt   pairing time
 * @param lastUsedAt  last authenticated request (updated with coarse granularity)
 * @param lastAddress client address of the last authenticated request, may be {@code null}
 * @param revokedAt   revocation time, or {@code null} while active
 */
public record Device(
        String id,
        UUID playerUuid,
        String name,
        String userAgent,
        Instant createdAt,
        Instant lastUsedAt,
        String lastAddress,
        Instant revokedAt) {

    public static final int MAX_NAME_LENGTH = 48;

    public Device {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(lastUsedAt, "lastUsedAt");
    }

    public boolean active() {
        return revokedAt == null;
    }
}
