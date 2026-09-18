package io.github.codaaaaaa.mecc.core.resources;

import java.util.Objects;

/**
 * Static, platform-independent description of a resource, derived once per distinct resource.
 *
 * @param id             identity
 * @param descriptionKey translation key of the item itself, or {@code null}; shown in resource details
 * @param name           display name as the game composes it, or {@code null} to fall back to the id
 * @param modId          owning mod
 * @param unit           amount unit for non-countable resources (e.g. fluids in buckets), or {@code null} for plain counts
 * @param iconKey        key for the icon service, e.g. {@code item/minecraft/iron_ingot}
 */
public record ResourceDescriptor(
        ResourceId id,
        String descriptionKey,
        ResourceText name,
        String modId,
        ResourceUnit unit,
        String iconKey) {

    public ResourceDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(modId, "modId");
        Objects.requireNonNull(iconKey, "iconKey");
    }

    /**
     * @param symbol        unit symbol, e.g. {@code B} for buckets
     * @param amountPerUnit raw amount in one unit, e.g. 1000 for buckets on Forge
     */
    public record ResourceUnit(String symbol, int amountPerUnit) {
        public ResourceUnit {
            Objects.requireNonNull(symbol, "symbol");
            if (amountPerUnit < 1) {
                throw new IllegalArgumentException("amountPerUnit must be positive");
            }
        }
    }
}
