package io.github.codaaaaaa.mecc.core.networks;

import io.github.codaaaaaa.mecc.core.permissions.NetworkRole;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface NetworkRepository {

    /** Every network record including anchors. */
    List<WebNetwork> listAll();

    Optional<WebNetwork> find(UUID id);

    /** Inserts the network with its anchors. Throws {@code DuplicateKeyException} if an anchor is already enrolled. */
    void insert(WebNetwork network);

    void rename(UUID id, String displayName);

    /** Deletes the record, its anchors, and its memberships. */
    boolean delete(UUID id);

    void updateObservation(UUID id, NetworkRecordStatus status, Instant lastSeenAt);

    /** Attaches an anchor. Returns {@code false} when the location is already an anchor of any network. */
    boolean addAnchor(UUID networkId, NetworkAnchor anchor);

    /** Removes an anchor of one specific network (a no-op if the location now belongs to another network). */
    void removeAnchor(UUID networkId, BlockLocation location);

    Optional<NetworkRole> memberRole(UUID networkId, UUID playerUuid);

    /** Network ID to role for every network the player is a member of. */
    Map<UUID, NetworkRole> membershipsOf(UUID playerUuid);

    List<NetworkMember> members(UUID networkId);

    void upsertMember(NetworkMember member);

    boolean removeMember(UUID networkId, UUID playerUuid);
}
