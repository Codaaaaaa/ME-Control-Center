package io.github.codaaaaaa.mecc.core.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceRepository {

    /** Inserts a device with its token verifier. Throws {@code DuplicateKeyException} on an ID collision. */
    void insert(Device device, String tokenHash);

    Optional<Device> findActiveByTokenHash(String tokenHash);

    Optional<Device> find(String id);

    /** Active devices of a player, most recently used first. */
    List<Device> listActive(UUID playerUuid);

    void touch(String id, Instant usedAt, String address);

    void rename(String id, String name);

    /** Returns {@code true} when an active device was revoked. */
    boolean revoke(String id, Instant now);

    /** Revokes every active device of a player except {@code keepId} (may be {@code null}). Returns the count. */
    int revokeAll(UUID playerUuid, String keepId, Instant now);
}
