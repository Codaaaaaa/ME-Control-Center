package io.github.codaaaaaa.mecc.core.resources;

import io.github.codaaaaaa.mecc.core.auth.Session;
import io.github.codaaaaaa.mecc.core.resources.ResourceViews.ResourceDetailView;
import io.github.codaaaaaa.mecc.core.resources.ResourceViews.ResourcePage;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Read access to network storage for the resource terminal. Requires {@code VIEW_TERMINAL}; reads come
 * from cached immutable snapshots, never from live world state on the calling thread.
 */
public interface ResourceService {

    CompletionStage<ResourcePage> page(Session session, UUID networkId, ResourceQuery query);

    /** @param resourceId text form of a {@link ResourceId} */
    CompletionStage<ResourceDetailView> detail(Session session, UUID networkId, String resourceId, String snapshotId, String locale);
}
