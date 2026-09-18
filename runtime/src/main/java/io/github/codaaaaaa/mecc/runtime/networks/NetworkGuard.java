package io.github.codaaaaaa.mecc.runtime.networks;

import io.github.codaaaaaa.mecc.core.auth.Session;
import io.github.codaaaaaa.mecc.core.error.ErrorCode;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import io.github.codaaaaaa.mecc.core.networks.NetworkReconciler.Resolution;
import io.github.codaaaaaa.mecc.core.networks.NetworkRecordStatus;
import io.github.codaaaaaa.mecc.core.permissions.NetworkAccess;
import io.github.codaaaaaa.mecc.core.permissions.NetworkCapability;
import io.github.codaaaaaa.mecc.core.persistence.DataStore;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Entry check for live network data (spec section 36: permission check on every network-specific request).
 * Resolves the caller's access and the loaded grid the network currently maps to.
 */
public final class NetworkGuard {
    private final DataStore store;
    private final NetworkDirectory directory;
    private final boolean adminOverride;

    public NetworkGuard(DataStore store, NetworkDirectory directory, boolean adminOverride) {
        this.store = store;
        this.directory = directory;
        this.adminOverride = adminOverride;
    }

    /**
     * @return the runtime key of the network's grid
     * @throws MeccException (as the future's failure) {@code NETWORK_NOT_FOUND}, {@code PERMISSION_DENIED},
     *                        {@code NETWORK_OFFLINE}, or {@code NETWORK_UNAVAILABLE}
     */
    public CompletableFuture<String> liveGrid(Session session, UUID networkId, NetworkCapability capability) {
        return access(session, networkId).thenApply(access -> {
            require(access, capability);
            return gridKey(networkId);
        });
    }

    /**
     * What the caller may do on the network, whether or not it is loaded.
     *
     * @throws MeccException (as the future's failure) {@code NETWORK_NOT_FOUND}
     */
    public CompletableFuture<NetworkAccess> access(Session session, UUID networkId) {
        return store.read(repos -> {
            if (repos.networks().find(networkId).isEmpty()) {
                throw notFound();
            }
            return NetworkAccess.resolve(
                            repos.networks().memberRole(networkId, session.user().playerUuid()).orElse(null),
                            session.serverAdmin(), adminOverride)
                    .orElseThrow(NetworkGuard::notFound);
        });
    }

    public static void require(NetworkAccess access, NetworkCapability capability) {
        if (!access.allows(capability)) {
            throw new MeccException(ErrorCode.PERMISSION_DENIED, "Your role on this network does not allow this action",
                    Map.of("requiredRole", capability.minimumRole().name()));
        }
    }

    /**
     * The runtime key of the network's grid right now.
     *
     * @throws MeccException {@code NETWORK_OFFLINE} or {@code NETWORK_UNAVAILABLE}
     */
    public String gridKey(UUID networkId) {
        NetworkDirectory.State state = directory.current();
        Resolution resolution = state == null ? null : state.result().resolutions().get(networkId);
        if (resolution == null || resolution.status() == NetworkRecordStatus.OFFLINE) {
            throw new MeccException(ErrorCode.NETWORK_OFFLINE, "The ME network is not loaded right now");
        }
        if (resolution.status() == NetworkRecordStatus.CONFLICT) {
            throw new MeccException(ErrorCode.NETWORK_UNAVAILABLE,
                    "The ME network's identity is ambiguous; live data is paused until it is resolved");
        }
        return resolution.grid().runtimeKey();
    }

    /** The grid key when the network is unambiguously loaded, without failing otherwise. */
    public Optional<String> onlineGridKey(UUID networkId) {
        NetworkDirectory.State state = directory.current();
        Resolution resolution = state == null ? null : state.result().resolutions().get(networkId);
        return resolution == null || resolution.status() != NetworkRecordStatus.ONLINE
                ? Optional.empty()
                : Optional.of(resolution.grid().runtimeKey());
    }

    private static MeccException notFound() {
        return new MeccException(ErrorCode.NETWORK_NOT_FOUND, "Network not found");
    }
}
