package io.github.codaaaaaa.mecc.core.permissions;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * What one user may do on one network.
 *
 * @param memberRole    the user's own membership role, or {@code null} when not a member
 * @param adminOverride whether server-admin override grants Owner-level access on top of that
 */
public record NetworkAccess(NetworkRole memberRole, boolean adminOverride) {

    /**
     * Resolves access, or empty when the user may not even know the network exists.
     *
     * @param memberRole            membership role, or {@code null}
     * @param serverAdmin           whether the user currently is a server admin
     * @param adminOverrideEnabled  {@code security.admin_override}
     */
    public static Optional<NetworkAccess> resolve(NetworkRole memberRole, boolean serverAdmin, boolean adminOverrideEnabled) {
        boolean override = serverAdmin && adminOverrideEnabled && memberRole != NetworkRole.OWNER;
        if (memberRole == null && !override) {
            return Optional.empty();
        }
        return Optional.of(new NetworkAccess(memberRole, override));
    }

    /** Role used for UI purposes: the strongest role available, including override. */
    public NetworkRole effectiveRole() {
        return adminOverride ? NetworkRole.OWNER : memberRole;
    }

    public boolean allows(NetworkCapability capability) {
        return adminOverride || memberAllows(capability);
    }

    /** Whether performing {@code capability} relies on admin override (and must therefore be audited as such). */
    public boolean requiresOverride(NetworkCapability capability) {
        return adminOverride && !memberAllows(capability);
    }

    public Set<NetworkCapability> capabilities() {
        Set<NetworkCapability> result = EnumSet.noneOf(NetworkCapability.class);
        for (NetworkCapability capability : NetworkCapability.values()) {
            if (allows(capability)) {
                result.add(capability);
            }
        }
        return result;
    }

    private boolean memberAllows(NetworkCapability capability) {
        return memberRole != null && memberRole.atLeast(capability.minimumRole());
    }
}
