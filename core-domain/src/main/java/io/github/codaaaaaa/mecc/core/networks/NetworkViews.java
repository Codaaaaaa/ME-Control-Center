package io.github.codaaaaaa.mecc.core.networks;

import io.github.codaaaaaa.mecc.core.auth.AuthViews.UserView;
import io.github.codaaaaaa.mecc.core.permissions.NetworkCapability;
import io.github.codaaaaaa.mecc.core.permissions.NetworkRole;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** API response models for ME networks. */
public final class NetworkViews {
    private NetworkViews() {
    }

    /** User-facing network state (spec section 6.1: Online / Offline / Degraded, plus identity conflicts). */
    public enum NetworkState {
        ONLINE,
        DEGRADED,
        OFFLINE,
        CONFLICT
    }

    /** Why a network is not simply {@link NetworkState#ONLINE}. */
    public enum StateReason {
        /** ME Control Center has not completed a discovery pass since startup. */
        NOT_DISCOVERED_YET,
        /** No anchor of the network is loaded. */
        NOT_LOADED,
        UNPOWERED,
        BOOTING,
        CONTROLLER_CONFLICT,
        SPLIT,
        MERGED
    }

    /**
     * @param role          effective role (includes admin override)
     * @param adminOverride access is granted through server-admin override
     * @param stateReason   {@code null} when {@code state == ONLINE}
     */
    public record NetworkSummaryView(
            UUID id,
            String displayName,
            UserView owner,
            NetworkRole role,
            boolean adminOverride,
            NetworkState state,
            StateReason stateReason,
            Instant lastSeenAt,
            Instant createdAt) {
    }

    /**
     * @param status           live grid status; {@code null} unless the network is resolved to a loaded grid
     * @param statusCapturedAt when {@code status} was captured
     * @param capabilities     what the caller may do on this network
     */
    public record NetworkDetailView(
            NetworkSummaryView network,
            GridStatus status,
            Instant statusCapturedAt,
            List<AnchorView> anchors,
            Set<NetworkCapability> capabilities) {
    }

    /**
     * @param owner   anchor node owner, or {@code null}
     * @param active  anchor is powered and has a channel; {@code null} when not loaded
     */
    public record AnchorView(String key, String dimension, int x, int y, int z, UserView owner, Boolean active) {
    }

    /**
     * An unenrolled loaded network the caller may claim.
     *
     * @param key        identifier to pass when claiming: the key of the network's first anchor
     * @param ownedByYou the caller owns at least one anchor (otherwise visible through admin rights)
     * @param nodeCount  grid size, to help tell networks apart
     */
    public record CandidateView(String key, List<AnchorView> anchors, boolean ownedByYou, boolean powered, int nodeCount) {
    }

    /**
     * @param primaryOwner the enrolling owner; cannot be removed or demoted
     * @param addedBy      who granted the role, or {@code null}
     */
    public record MemberView(UserView user, NetworkRole role, boolean primaryOwner, Instant addedAt, UserView addedBy) {
    }
}
