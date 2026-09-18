package io.github.codaaaaaa.mecc.platform;

import io.github.codaaaaaa.mecc.core.networks.BlockLocation;
import io.github.codaaaaaa.mecc.core.networks.DiscoverySnapshot;
import java.util.Collection;

/**
 * Discovers loaded ME networks (spec sections 29 and 43).
 *
 * <p>Implementations must not walk the world or every grid node: discovery should be proportional to
 * the number of anchors, because it runs periodically inside a tick.
 */
public interface NetworkPlatform {

    /**
     * Captures every loaded grid that has at least one anchor, plus the state of each known anchor
     * location that is not part of those grids.
     *
     * @param knownAnchors anchor locations of existing ME Control Center network records
     */
    @ServerThreadOnly
    DiscoverySnapshot discover(Collection<BlockLocation> knownAnchors);
}
