package io.github.codaaaaaa.mecc.core.permissions;

/**
 * Actions guarded per network, each with the minimum role that may perform it.
 * Mirrors the capability table in spec section 28.
 */
public enum NetworkCapability {
    VIEW_NETWORK(NetworkRole.VIEWER),
    VIEW_TERMINAL(NetworkRole.VIEWER),
    VIEW_CHARTS(NetworkRole.VIEWER),
    MANAGE_WATCHLIST(NetworkRole.VIEWER),
    SUBMIT_CRAFT(NetworkRole.OPERATOR),
    CANCEL_OWN_CRAFT(NetworkRole.OPERATOR),
    CANCEL_ANY_CRAFT(NetworkRole.MANAGER),
    PATTERN_STUDIO(NetworkRole.MANAGER),
    DEPLOY_PATTERNS(NetworkRole.MANAGER),
    PROVIDER_SETTINGS(NetworkRole.MANAGER),
    SHARE_NETWORK(NetworkRole.OWNER),
    MANAGE_MEMBERS(NetworkRole.OWNER),
    RENAME_NETWORK(NetworkRole.OWNER),
    DELETE_NETWORK(NetworkRole.OWNER),
    VIEW_AUDIT_LOG(NetworkRole.OWNER);

    private final NetworkRole minimumRole;

    NetworkCapability(NetworkRole minimumRole) {
        this.minimumRole = minimumRole;
    }

    public NetworkRole minimumRole() {
        return minimumRole;
    }
}
