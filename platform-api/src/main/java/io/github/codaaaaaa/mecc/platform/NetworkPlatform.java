package io.github.codaaaaaa.mecc.platform;

import io.github.codaaaaaa.mecc.core.explorer.DeviceKind;
import io.github.codaaaaaa.mecc.core.networks.BlockLocation;
import io.github.codaaaaaa.mecc.core.networks.DiscoverySnapshot;
import io.github.codaaaaaa.mecc.core.networks.GridStatus;
import io.github.codaaaaaa.mecc.core.resources.ResourceDescriptor;
import io.github.codaaaaaa.mecc.core.resources.ResourceText;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

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

    /**
     * Describes the devices of one loaded grid for the Network Explorer (spec section 26). Unlike discovery this
     * walks the grid's nodes, so it runs only on request and its result is cached.
     *
     * @param gridKey runtime key of the grid
     */
    @ServerThreadOnly
    DeviceCapture describeDevices(String gridKey);

    /** Devices of one kind that are the same block. Optional values are {@code null} when unknown, never zero. */
    record DeviceGroupState(DeviceKind kind, ResourceDescriptor item, int count, int offline, Integer channels,
                            Double idlePower, List<BlockLocation> locations, boolean truncated) {
        /** Locations reported per group; a network can hold thousands of the same block. */
        public static final int MAX_LOCATIONS = 32;

        public DeviceGroupState {
            Objects.requireNonNull(kind, "kind");
            locations = List.copyOf(locations);
        }
    }

    record DeviceCapture(Instant capturedAt, GridStatus status, List<DeviceGroupState> groups, int nodes,
                         int offlineNodes) {
        public DeviceCapture {
            Objects.requireNonNull(capturedAt, "capturedAt");
            groups = List.copyOf(groups);
        }
    }

    /**
     * The in-game ME Requesters of one loaded grid (the optional ME Requester mod), with what each of their
     * request slots keeps in stock. They do the same as this network's Keep Stock rules, configured on a block,
     * so ME Control Center shows both side by side. Adapters without that mod report nothing.
     *
     * @param gridKey runtime key of the grid
     */
    @ServerThreadOnly
    default RequesterCapture captureRequesters(String gridKey) {
        return new RequesterCapture(false, List.of());
    }

    /**
     * Empties one request slot of an ME Requester, as taking its request out in game would.
     *
     * @return {@code false} when that requester or slot is no longer there
     */
    @ServerThreadOnly
    default boolean clearRequest(String gridKey, String requesterId, int slot) {
        return false;
    }

    /**
     * One request slot of an ME Requester.
     *
     * @param amount  the stock level it keeps
     * @param batch   how much it asks for at a time
     * @param enabled whether the requester acts on this slot
     * @param status  what it is doing about the slot ({@code IDLE}, {@code MISSING}, {@code LINK}, ...), or
     *                {@code null} when the mod does not say
     * @param stored  what the requester last saw in the network, or {@code null} when unknown
     */
    record RequestState(int slot, ResourceDescriptor resource, long amount, long batch, boolean enabled, String status,
                        Long stored) {
        public RequestState {
            Objects.requireNonNull(resource, "resource");
        }
    }

    /**
     * One ME Requester block.
     *
     * @param id       position-based, stable while the requester stays in place
     * @param name     the name the Requester Terminal shows, or {@code null}
     * @param online   powered and has a channel
     * @param requests only the slots that ask for something
     */
    record RequesterState(String id, ResourceText name, BlockLocation location, boolean online,
                          List<RequestState> requests) {
        public RequesterState {
            Objects.requireNonNull(id, "id");
            requests = List.copyOf(requests);
        }
    }

    /** @param supported whether this server can read ME Requesters at all, i.e. the mod is installed */
    record RequesterCapture(boolean supported, List<RequesterState> requesters) {
        public RequesterCapture {
            requesters = List.copyOf(requesters);
        }
    }
}
