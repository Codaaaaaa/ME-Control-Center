package io.github.codaaaaaa.mecc.forge.ae2;

import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridService;
import java.util.Collection;

/**
 * ME Control Center's per-grid AE2 service. AE2 creates one instance for every grid, which lets ME Control Center know the
 * anchor nodes of each grid incrementally instead of scanning the world (spec section 48).
 */
public interface MeccGridTracker extends IGridService {

    /** Wireless Access Point nodes currently in this grid. Server thread only. */
    Collection<IGridNode> anchorNodes();
}
