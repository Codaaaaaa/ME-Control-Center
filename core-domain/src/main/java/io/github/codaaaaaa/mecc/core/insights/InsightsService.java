package io.github.codaaaaaa.mecc.core.insights;

import io.github.codaaaaaa.mecc.core.auth.Session;
import io.github.codaaaaaa.mecc.core.insights.InsightsViews.SeriesSet;
import io.github.codaaaaaa.mecc.core.insights.InsightsViews.WatchEntryView;
import io.github.codaaaaaa.mecc.core.insights.InsightsViews.Watchlist;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Personal watchlists and their history (spec sections 21-22). Network methods check access first
 * ({@code NETWORK_NOT_FOUND}, {@code PERMISSION_DENIED}).
 */
public interface InsightsService {

    /** The caller's entries on a network, with current amounts when the network is loaded. */
    CompletionStage<Watchlist> watchlist(Session session, UUID networkId, String locale);

    /**
     * Watches a resource the network currently stores or can craft. Watching it again returns the existing entry.
     * Fails with {@code NETWORK_OFFLINE}, {@code RESOURCE_NOT_FOUND}, or {@code CONFLICT} beyond the per-player limit.
     */
    CompletionStage<WatchEntryView> watch(Session session, UUID networkId, String resourceId, String locale);

    /** Removes one of the caller's entries; {@code NOT_FOUND} for anyone else's. */
    CompletionStage<Void> unwatch(Session session, UUID entryId);

    /**
     * History of the caller's watched resources on a network, or of just {@code resourceId} when given.
     * Works while the network is offline.
     */
    CompletionStage<SeriesSet> series(Session session, UUID networkId, InsightsRange range, String resourceId);
}
