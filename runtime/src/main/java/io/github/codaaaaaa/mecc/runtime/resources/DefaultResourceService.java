package io.github.codaaaaaa.mecc.runtime.resources;

import io.github.codaaaaaa.mecc.assets.ResourceNames;
import io.github.codaaaaaa.mecc.core.assets.IconService;
import io.github.codaaaaaa.mecc.core.auth.Session;
import io.github.codaaaaaa.mecc.core.error.ErrorCode;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import io.github.codaaaaaa.mecc.core.permissions.NetworkCapability;
import io.github.codaaaaaa.mecc.core.resources.ResourceDescriptor;
import io.github.codaaaaaa.mecc.core.resources.ResourceQuery;
import io.github.codaaaaaa.mecc.core.resources.ResourceService;
import io.github.codaaaaaa.mecc.core.resources.ResourceViews.ResourceDetailView;
import io.github.codaaaaaa.mecc.core.resources.ResourceViews.ResourcePage;
import io.github.codaaaaaa.mecc.core.resources.ResourceViews.ResourceView;
import io.github.codaaaaaa.mecc.runtime.networks.NetworkGuard;
import io.github.codaaaaaa.mecc.runtime.resources.ResourceSnapshots.Snapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Resource terminal reads: permission check, cached snapshot, localized catalog, page. */
public final class DefaultResourceService implements ResourceService {
    public static final Set<String> LOCALES = Set.of("en_us", "zh_cn");

    private final NetworkGuard guard;
    private final ResourceSnapshots snapshots;
    private final TagIndex tags;
    private final Supplier<ResourceNames> names;
    private final Map<String, String> modNames;
    private final IconService icons;
    private final Executor workers;

    public DefaultResourceService(NetworkGuard guard, ResourceSnapshots snapshots, TagIndex tags, Supplier<ResourceNames> names,
                                  Map<String, String> modNames, IconService icons, Executor workers) {
        this.guard = guard;
        this.snapshots = snapshots;
        this.tags = tags;
        this.names = names;
        this.modNames = Map.copyOf(modNames);
        this.icons = icons;
        this.workers = workers;
    }

    @Override
    public CompletionStage<ResourcePage> page(Session session, UUID networkId, ResourceQuery query) {
        String locale = locale(query.locale());
        return catalog(session, networkId, query.snapshotId(), locale).thenApply(prepared -> {
            ResourceCatalog catalog = prepared.catalog();
            int[] matches = catalog.query(query.search(), query.type(), query.sort(), query.descending());
            int from = Math.min(query.offset(), matches.length);
            int to = Math.min(matches.length, from + query.limit());
            List<ResourceView> entries = new ArrayList<>(to - from);
            for (int i = from; i < to; i++) {
                entries.add(catalog.view(matches[i]));
            }
            return new ResourcePage(prepared.snapshot().id(), catalog.index().capturedAt(), matches.length, from,
                    entries, icons.assetVersion());
        });
    }

    @Override
    public CompletionStage<ResourceDetailView> detail(Session session, UUID networkId, String resourceId, String snapshotId,
                                                      String locale) {
        return catalog(session, networkId, snapshotId, locale(locale)).thenApply(prepared -> {
            ResourceCatalog catalog = prepared.catalog();
            int position = catalog.find(resourceId == null ? "" : resourceId)
                    .orElseThrow(() -> new MeccException(ErrorCode.RESOURCE_NOT_FOUND,
                            "This resource is not in the network's storage"));
            ResourceDescriptor descriptor = catalog.index().descriptor(position);
            return new ResourceDetailView(catalog.view(position), descriptor.id().registryId(), descriptor.id().variant(),
                    descriptor.descriptionKey(), catalog.tags(position), prepared.snapshot().id(),
                    catalog.index().capturedAt(), icons.assetVersion());
        });
    }

    private record Prepared(Snapshot snapshot, ResourceCatalog catalog) {
    }

    private CompletableFuture<Prepared> catalog(Session session, UUID networkId, String snapshotId, String locale) {
        return guard.liveGrid(session, networkId, NetworkCapability.VIEW_TERMINAL)
                .thenCompose(gridKey -> snapshots.find(networkId, snapshotId)
                        .map(CompletableFuture::completedFuture)
                        .orElseGet(() -> snapshots.latest(networkId, gridKey)))
                .thenCompose(snapshot -> tags.tags().thenApplyAsync(currentTags -> new Prepared(snapshot,
                        snapshot.catalog(locale, currentTags.version(), () -> new ResourceCatalog(snapshot.index(), locale,
                                names.get(), modNames, currentTags.byResource(), currentTags.version()))), workers));
    }

    static String locale(String requested) {
        return requested != null && LOCALES.contains(requested) ? requested : ResourceNames.FALLBACK_LOCALE;
    }
}
