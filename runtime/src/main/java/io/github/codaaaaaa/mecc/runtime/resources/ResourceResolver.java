package io.github.codaaaaaa.mecc.runtime.resources;

import io.github.codaaaaaa.mecc.core.error.ErrorCode;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import io.github.codaaaaaa.mecc.core.resources.ResourceDescriptor;
import io.github.codaaaaaa.mecc.core.resources.ResourceId;
import io.github.codaaaaaa.mecc.core.resources.ResourceIndex;
import io.github.codaaaaaa.mecc.runtime.networks.NetworkGuard;
import io.github.codaaaaaa.mecc.runtime.patterns.RecipeLibrary;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Finds what a resource ID names, for presets and alert rules that must stay readable while the resource is out of
 * storage: the network's storage first (stored or craftable, with the exact variant), else every item and fluid
 * registered on the server.
 */
public final class ResourceResolver {
    private final NetworkGuard guard;
    private final ResourceSnapshots snapshots;
    private final RecipeLibrary library;

    public ResourceResolver(NetworkGuard guard, ResourceSnapshots snapshots, RecipeLibrary library) {
        this.guard = guard;
        this.snapshots = snapshots;
        this.library = library;
    }

    /** Fails with {@code RESOURCE_NOT_FOUND} when neither knows it. */
    public CompletableFuture<ResourceDescriptor> resolve(UUID networkId, ResourceId id) {
        CompletableFuture<ResourceIndex> index = guard.onlineGridKey(networkId)
                .map(gridKey -> snapshots.latest(networkId, gridKey).thenApply(ResourceSnapshots.Snapshot::index)
                        .exceptionally(error -> null))
                .orElseGet(() -> CompletableFuture.completedFuture(null));
        return index.thenCompose(current -> {
            int position = current == null ? -1 : current.indexOf(id);
            if (position >= 0) {
                return CompletableFuture.completedFuture(current.descriptor(position));
            }
            return library.book().thenApply(book -> book.registered(id).orElseThrow(() ->
                    new MeccException(ErrorCode.RESOURCE_NOT_FOUND, "No such item or fluid on this server")));
        });
    }
}
