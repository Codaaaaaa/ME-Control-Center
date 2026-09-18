package io.github.codaaaaaa.mecc.core.resources;

import io.github.codaaaaaa.mecc.core.resources.ResourceDescriptor.ResourceUnit;
import java.time.Instant;
import java.util.List;

/** API response models for the resource terminal. */
public final class ResourceViews {
    private ResourceViews() {
    }

    /**
     * One terminal tile.
     *
     * @param name      plain display name, always present
     * @param nameSpans the same name split into styled runs, or {@code null} when it carries no styling at
     *                  all - which is the common case, so most entries stay small
     * @param crafting  amount requested by running crafting jobs, or {@code null}
     * @param unit      display unit, or {@code null} for plain counts
     */
    public record ResourceView(
            String id,
            String type,
            String name,
            List<NameSpan> nameSpans,
            String modId,
            String modName,
            long amount,
            boolean craftable,
            Long crafting,
            ResourceUnit unit,
            String iconKey) {
    }

    /**
     * How to show a resource that is referenced rather than listed, e.g. a crafting output or ingredient.
     *
     * @param nameSpans styled runs of {@code name}, or {@code null} when it carries no styling
     * @param unit      display unit, or {@code null} for plain counts
     */
    public record ResourceLabel(
            String id,
            String type,
            String name,
            List<NameSpan> nameSpans,
            String modId,
            String modName,
            ResourceUnit unit,
            String iconKey) {
    }

    /**
     * @param snapshotId    pass back to read further pages from the same snapshot
     * @param total         entries matching the query
     * @param assetVersion  changes when installed assets change; part of icon URLs so they can be cached forever
     */
    public record ResourcePage(
            String snapshotId,
            Instant capturedAt,
            int total,
            int offset,
            List<ResourceView> entries,
            String assetVersion) {
    }

    /**
     * @param registryId     e.g. {@code minecraft:iron_ingot}
     * @param variant        variant hash, or {@code null}
     * @param descriptionKey translation key, or {@code null}
     * @param tags           tags such as {@code forge:ingots/iron}
     */
    public record ResourceDetailView(
            ResourceView resource,
            String registryId,
            String variant,
            String descriptionKey,
            List<String> tags,
            String snapshotId,
            Instant capturedAt,
            String assetVersion) {
    }
}
