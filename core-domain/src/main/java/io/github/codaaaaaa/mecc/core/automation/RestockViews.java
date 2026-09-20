package io.github.codaaaaaa.mecc.core.automation;

import io.github.codaaaaaa.mecc.core.networks.BlockLocation;
import io.github.codaaaaaa.mecc.core.resources.ResourceViews.ResourceLabel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** API response models for Auto Restock (spec section 25). */
public final class RestockViews {
    private RestockViews() {
    }

    /**
     * @param createdBy name of the player the jobs are requested as
     * @param stored    amount in network storage right now, or {@code null} when it could not be read
     */
    public record RuleView(UUID id, UUID networkId, String createdBy, ResourceLabel resource, long minimum,
                           long restockTo, String cpuId, int cooldownMinutes, boolean enabled, Long stored,
                           Instant lastRunAt, UUID lastOrderId, int failures, Instant pausedUntil, String lastError,
                           Instant createdAt) {
    }

    /**
     * @param serverEnabled whether the server allows automation at all ({@code automation.auto_restock_enabled})
     * @param limit         rules this network may have
     */
    public record RuleList(List<RuleView> rules, int limit, boolean serverEnabled, int maxActiveJobs,
                           String assetVersion) {
    }

    /**
     * One request slot of an in-game ME Requester (the optional ME Requester mod).
     *
     * @param amount  the stock level it keeps
     * @param batch   how much it asks for at a time
     * @param enabled whether the requester acts on this slot
     * @param status  what it is doing about the slot ({@code IDLE}, {@code MISSING}, {@code LINK}, ...), or null
     * @param stored  what the requester last saw in the network, or {@code null} when unknown
     */
    public record RequesterRequestView(int slot, ResourceLabel resource, long amount, long batch, boolean enabled,
                                       String status, Long stored) {
    }

    /** One ME Requester block of the network, with the slots that ask for something. */
    public record RequesterView(String id, String name, BlockLocation location, boolean online,
                                List<RequesterRequestView> requests) {
    }

    /** @param supported whether requesters can be read at all: the mod is installed and the network is loaded */
    public record RequesterList(List<RequesterView> requesters, boolean supported, String assetVersion) {
    }
}
