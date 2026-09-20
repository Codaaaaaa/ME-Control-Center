package io.github.codaaaaaa.mecc.core.explorer;

import io.github.codaaaaaa.mecc.core.auth.Session;
import io.github.codaaaaaa.mecc.core.networks.BlockLocation;
import io.github.codaaaaaa.mecc.core.networks.GridStatus;
import io.github.codaaaaaa.mecc.core.resources.ResourceViews.ResourceLabel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Network Explorer (spec section 26): what the network is made of and what is offline. Requires
 * {@code VIEW_NETWORK}. Values a platform cannot report reliably are {@code null}, never zero.
 */
public interface ExplorerService {

    CompletionStage<NetworkMap> map(Session session, UUID networkId, String locale);

    /**
     * Devices of one kind that are the same block, counted together.
     *
     * @param item      the device as an item, or {@code null} when it has no representation
     * @param offline   how many of them have no power or no channel
     * @param channels  channels they use in total, or {@code null} when unknown
     * @param idlePower AE/t they draw idle in total, or {@code null} when unknown
     * @param locations where they are, up to a limit
     * @param truncated whether {@code locations} was cut short
     */
    record DeviceGroup(DeviceKind kind, ResourceLabel item, int count, int offline, Integer channels, Double idlePower,
                       List<BlockLocation> locations, boolean truncated) {
    }

    /**
     * @param nodes        grid nodes in total
     * @param offlineNodes nodes without power or channel
     */
    record NetworkMap(Instant capturedAt, GridStatus status, List<DeviceGroup> devices, int nodes, int offlineNodes,
                      String assetVersion) {
    }
}
