package io.github.codaaaaaa.mecc.runtime.insights;

import io.github.codaaaaaa.mecc.core.auth.Session;
import io.github.codaaaaaa.mecc.core.config.AnalyticsConfig;
import io.github.codaaaaaa.mecc.core.error.ErrorCode;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import io.github.codaaaaaa.mecc.core.insights.InsightsRange;
import io.github.codaaaaaa.mecc.core.insights.InsightsService;
import io.github.codaaaaaa.mecc.core.insights.InsightsViews.SeriesSet;
import io.github.codaaaaaa.mecc.core.insights.InsightsViews.SeriesView;
import io.github.codaaaaaa.mecc.core.insights.InsightsViews.WatchEntryView;
import io.github.codaaaaaa.mecc.core.insights.InsightsViews.Watchlist;
import io.github.codaaaaaa.mecc.core.insights.SeriesPlanner;
import io.github.codaaaaaa.mecc.core.insights.WatchEntry;
import io.github.codaaaaaa.mecc.core.permissions.NetworkCapability;
import io.github.codaaaaaa.mecc.core.persistence.DataStore;
import io.github.codaaaaaa.mecc.core.resources.ResourceId;
import io.github.codaaaaaa.mecc.core.resources.ResourceIndex;
import io.github.codaaaaaa.mecc.runtime.crafting.ResourceLabels;
import io.github.codaaaaaa.mecc.runtime.networks.NetworkGuard;
import io.github.codaaaaaa.mecc.runtime.resources.ResourceSnapshots;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Watchlists and history reads (spec sections 21-22). */
public final class DefaultInsightsService implements InsightsService {
    private static final Duration SHORTEST_MAX_RANGE = Duration.ofHours(1);

    private final DataStore store;
    private final NetworkGuard guard;
    private final ResourceSnapshots snapshots;
    private final ResourceLabels labels;
    private final Supplier<String> assetVersion;
    private final AnalyticsConfig config;
    private final Clock clock;

    public DefaultInsightsService(DataStore store, NetworkGuard guard, ResourceSnapshots snapshots, ResourceLabels labels,
                                  Supplier<String> assetVersion, AnalyticsConfig config, Clock clock) {
        this.store = store;
        this.guard = guard;
        this.snapshots = snapshots;
        this.labels = labels;
        this.assetVersion = assetVersion;
        this.config = config;
        this.clock = clock;
    }

    @Override
    public CompletionStage<Watchlist> watchlist(Session session, UUID networkId, String locale) {
        UUID player = session.user().playerUuid();
        return guard.access(session, networkId)
                .thenCompose(access -> {
                    NetworkGuard.require(access, NetworkCapability.VIEW_CHARTS);
                    return store.read(repos -> repos.watchlist().list(player, networkId));
                })
                .thenCompose(entries -> liveIndex(networkId).thenApply(index -> {
                    Map<ResourceId, Integer> positions = positions(index,
                            entries.stream().map(entry -> entry.resource().resourceId()).collect(Collectors.toSet()));
                    return new Watchlist(entries.stream().map(entry -> view(entry, index, positions, locale)).toList(),
                            index == null ? null : index.capturedAt(), config.maxWatchlistEntriesPerUser(),
                            assetVersion.get());
                }));
    }

    /** The network's current storage, or {@code null} when it cannot be read. Never fails. */
    private CompletableFuture<ResourceIndex> liveIndex(UUID networkId) {
        return guard.onlineGridKey(networkId)
                .map(gridKey -> snapshots.latest(networkId, gridKey)
                        .thenApply(snapshot -> snapshot.index())
                        .exceptionally(error -> null))
                .orElseGet(() -> CompletableFuture.completedFuture(null));
    }

    @Override
    public CompletionStage<WatchEntryView> watch(Session session, UUID networkId, String resourceId, String locale) {
        ResourceId resource = ResourceId.parse(resourceId == null ? "" : resourceId.strip())
                .orElseThrow(() -> MeccException.validation("resourceId", "Not a valid resource ID"));
        UUID player = session.user().playerUuid();
        return guard.liveGrid(session, networkId, NetworkCapability.MANAGE_WATCHLIST)
                .thenCompose(gridKey -> snapshots.latest(networkId, gridKey))
                .thenCompose(snapshot -> {
                    ResourceIndex index = snapshot.index();
                    Integer position = positions(index, Set.of(resource)).get(resource);
                    if (position == null) {
                        throw new MeccException(ErrorCode.RESOURCE_NOT_FOUND,
                                "The network neither stores nor can craft this resource");
                    }
                    WatchEntry created = new WatchEntry(UUID.randomUUID(), player, networkId,
                            labels.target(index.descriptor(position)), clock.instant());
                    return store.write(repos -> {
                        var existing = repos.watchlist().find(player, networkId, resource);
                        if (existing.isPresent()) {
                            return existing.get();
                        }
                        if (repos.watchlist().countByPlayer(player) >= config.maxWatchlistEntriesPerUser()) {
                            throw new MeccException(ErrorCode.CONFLICT, "Your watchlist is full",
                                    Map.of("limit", config.maxWatchlistEntriesPerUser()));
                        }
                        repos.watchlist().insert(created);
                        return created;
                    }).thenApply(entry -> view(entry, index, Map.of(resource, position), locale));
                });
    }

    /** Where each wanted resource sits in the index, in one pass. Empty for a {@code null} index. */
    private static Map<ResourceId, Integer> positions(ResourceIndex index, Set<ResourceId> wanted) {
        Map<ResourceId, Integer> positions = new HashMap<>();
        for (int i = 0; index != null && i < index.size() && positions.size() < wanted.size(); i++) {
            ResourceId id = index.descriptor(i).id();
            if (wanted.contains(id)) {
                positions.put(id, i);
            }
        }
        return positions;
    }

    @Override
    public CompletionStage<Void> unwatch(Session session, UUID entryId) {
        UUID player = session.user().playerUuid();
        return store.write(repos -> {
            boolean own = repos.watchlist().find(entryId).filter(entry -> entry.playerUuid().equals(player)).isPresent();
            if (!own || !repos.watchlist().delete(entryId)) {
                throw new MeccException(ErrorCode.NOT_FOUND, "Watchlist entry not found");
            }
            return null;
        });
    }

    @Override
    public CompletionStage<SeriesSet> series(Session session, UUID networkId, InsightsRange range, String resourceId) {
        UUID player = session.user().playerUuid();
        Instant now = clock.instant();
        return guard.access(session, networkId).thenCompose(access -> {
            NetworkGuard.require(access, NetworkCapability.VIEW_CHARTS);
            return store.read(repos -> {
                List<WatchEntry> entries = repos.watchlist().list(player, networkId).stream()
                        .filter(entry -> resourceId == null || entry.resource().resourceId().toString().equals(resourceId))
                        .toList();
                Duration length = range.length();
                if (length == null) {
                    Instant oldest = now;
                    for (WatchEntry entry : entries) {
                        Instant first = repos.samples().oldest(networkId, entry.resource().resourceId()).orElse(now);
                        oldest = first.isBefore(oldest) ? first : oldest;
                    }
                    length = Duration.between(oldest, now);
                    length = length.compareTo(SHORTEST_MAX_RANGE) < 0 ? SHORTEST_MAX_RANGE : length;
                }
                SeriesPlanner.Plan plan = SeriesPlanner.plan(length, config);
                Instant from = now.minus(length);
                Instant to = now.plusMillis(1);
                List<SeriesView> series = new ArrayList<>(entries.size());
                for (WatchEntry entry : entries) {
                    series.add(new SeriesView(entry.id().toString(), entry.resource().resourceId().toString(),
                            SeriesPlanner.withGaps(repos.samples().series(networkId, entry.resource().resourceId(),
                                    plan.source(), from, to, plan.stepMillis()), plan.stepMillis())));
                }
                return new SeriesSet(range.key(), from, now, plan.stepMillis() / 1000, plan.source(), config.enabled(),
                        series);
            });
        });
    }

    /** A resource the loaded network does not hold has amount 0; without an index amounts are unknown. */
    private WatchEntryView view(WatchEntry entry, ResourceIndex index, Map<ResourceId, Integer> positions, String locale) {
        Long amount = null;
        Boolean craftable = null;
        Long crafting = null;
        if (index != null) {
            Integer i = positions.get(entry.resource().resourceId());
            amount = i == null ? 0L : index.amount(i);
            craftable = i != null && index.craftable(i);
            crafting = i == null || index.craftingAmount(i) == ResourceIndex.NOT_CRAFTING ? null : index.craftingAmount(i);
        }
        return new WatchEntryView(entry.id().toString(), entry.networkId().toString(),
                labels.label(entry.resource(), locale(locale)), entry.createdAt(), amount, craftable, crafting);
    }

    private static String locale(String locale) {
        return locale == null || locale.isBlank() ? "en_us" : locale;
    }
}
