package io.github.codaaaaaa.mecc.core.crafting;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A player's crafting preset (spec section 24). Running one still calculates and confirms like any request; it
 * never submits on its own.
 *
 * @param cpuId preferred CPU, or {@code null} for automatic selection
 * @param notes free text, may be empty
 */
public record SavedOrder(UUID id, UUID playerUuid, UUID networkId, String name, OrderTarget target, long amount,
                         String cpuId, String notes, Instant createdAt, Instant updatedAt) {
    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_NOTES_LENGTH = 1000;
    /** Presets one player may keep across all networks. */
    public static final int MAX_PER_PLAYER = 200;

    public SavedOrder {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(notes, "notes");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
