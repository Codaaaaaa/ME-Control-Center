package io.github.codaaaaaa.mecc.core.permissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NetworkAccessTest {

    /** The capability table of spec section 28, row by row. */
    @Test
    void roleMatrixMatchesSpecification() {
        assertCapabilities(NetworkRole.VIEWER, EnumSet.of(
                NetworkCapability.VIEW_NETWORK, NetworkCapability.VIEW_TERMINAL, NetworkCapability.VIEW_CHARTS,
                NetworkCapability.MANAGE_WATCHLIST));
        assertCapabilities(NetworkRole.OPERATOR, EnumSet.of(
                NetworkCapability.VIEW_NETWORK, NetworkCapability.VIEW_TERMINAL, NetworkCapability.VIEW_CHARTS,
                NetworkCapability.MANAGE_WATCHLIST, NetworkCapability.SUBMIT_CRAFT, NetworkCapability.CANCEL_OWN_CRAFT));
        assertCapabilities(NetworkRole.MANAGER, EnumSet.of(
                NetworkCapability.VIEW_NETWORK, NetworkCapability.VIEW_TERMINAL, NetworkCapability.VIEW_CHARTS,
                NetworkCapability.MANAGE_WATCHLIST, NetworkCapability.SUBMIT_CRAFT, NetworkCapability.CANCEL_OWN_CRAFT,
                NetworkCapability.CANCEL_ANY_CRAFT, NetworkCapability.PATTERN_STUDIO, NetworkCapability.DEPLOY_PATTERNS,
                NetworkCapability.PROVIDER_SETTINGS, NetworkCapability.MANAGE_AUTOMATION));
        assertCapabilities(NetworkRole.OWNER, EnumSet.allOf(NetworkCapability.class));
    }

    @Test
    void nonMembersHaveNoAccess() {
        assertTrue(NetworkAccess.resolve(null, false, true).isEmpty());
        assertTrue(NetworkAccess.resolve(null, true, false).isEmpty(), "admin without override enabled");
    }

    @Test
    void adminOverrideGrantsOwnerAccessAndIsDetectable() {
        NetworkAccess admin = NetworkAccess.resolve(null, true, true).orElseThrow();
        assertEquals(NetworkRole.OWNER, admin.effectiveRole());
        assertTrue(admin.allows(NetworkCapability.MANAGE_MEMBERS));
        assertTrue(admin.requiresOverride(NetworkCapability.MANAGE_MEMBERS));

        NetworkAccess viewerAdmin = NetworkAccess.resolve(NetworkRole.VIEWER, true, true).orElseThrow();
        assertFalse(viewerAdmin.requiresOverride(NetworkCapability.VIEW_TERMINAL), "own role suffices");
        assertTrue(viewerAdmin.requiresOverride(NetworkCapability.SUBMIT_CRAFT));

        NetworkAccess ownerAdmin = NetworkAccess.resolve(NetworkRole.OWNER, true, true).orElseThrow();
        assertFalse(ownerAdmin.adminOverride());
    }

    private static void assertCapabilities(NetworkRole role, Set<NetworkCapability> expected) {
        NetworkAccess access = NetworkAccess.resolve(role, false, true).orElseThrow();
        assertEquals(expected, access.capabilities(), role.name());
        assertFalse(access.adminOverride());
    }
}
