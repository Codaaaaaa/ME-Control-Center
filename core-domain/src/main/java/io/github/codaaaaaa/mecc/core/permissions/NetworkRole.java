package io.github.codaaaaaa.mecc.core.permissions;

/** Per-network roles, ordered from least to most privileged (spec section 28). */
public enum NetworkRole {
    VIEWER,
    OPERATOR,
    MANAGER,
    OWNER;

    public boolean atLeast(NetworkRole other) {
        return compareTo(other) >= 0;
    }
}
