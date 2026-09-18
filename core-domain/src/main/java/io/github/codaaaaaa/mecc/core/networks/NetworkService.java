package io.github.codaaaaaa.mecc.core.networks;

import io.github.codaaaaaa.mecc.core.auth.Session;
import io.github.codaaaaaa.mecc.core.networks.NetworkViews.CandidateView;
import io.github.codaaaaaa.mecc.core.networks.NetworkViews.MemberView;
import io.github.codaaaaaa.mecc.core.networks.NetworkViews.NetworkDetailView;
import io.github.codaaaaaa.mecc.core.networks.NetworkViews.NetworkSummaryView;
import io.github.codaaaaaa.mecc.core.permissions.NetworkRole;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Network enrollment, visibility, and membership. Every network-specific method checks the caller's
 * access and fails with {@code NETWORK_NOT_FOUND} when the caller may not know the network exists,
 * or {@code PERMISSION_DENIED} when their role is insufficient.
 */
public interface NetworkService {

    /** Networks the caller may access. */
    CompletionStage<List<NetworkSummaryView>> list(Session session);

    /** Loaded, unenrolled networks the caller may claim. */
    CompletionStage<List<CandidateView>> candidates(Session session);

    /** Enrolls the loaded network containing the anchor {@code candidateKey}; the caller becomes its owner. */
    CompletionStage<NetworkDetailView> claim(Session session, String candidateKey, String displayName);

    CompletionStage<NetworkDetailView> get(Session session, UUID networkId);

    CompletionStage<NetworkSummaryView> rename(Session session, UUID networkId, String displayName);

    /** Removes the ME Control Center record (not anything in Minecraft). */
    CompletionStage<Void> delete(Session session, UUID networkId);

    CompletionStage<List<MemberView>> members(Session session, UUID networkId);

    /**
     * Adds or updates a member.
     *
     * @param player player name or UUID; must have used ME Control Center before or be online
     */
    CompletionStage<MemberView> putMember(Session session, UUID networkId, String player, NetworkRole role);

    /** Removes a member. Members may always remove themselves, except the primary owner. */
    CompletionStage<Void> removeMember(Session session, UUID networkId, UUID playerUuid);
}
