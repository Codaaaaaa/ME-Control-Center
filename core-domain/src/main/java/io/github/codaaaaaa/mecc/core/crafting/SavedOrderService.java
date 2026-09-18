package io.github.codaaaaaa.mecc.core.crafting;

import io.github.codaaaaaa.mecc.core.auth.Session;
import io.github.codaaaaaa.mecc.core.crafting.CraftingViews.SavedOrderList;
import io.github.codaaaaaa.mecc.core.crafting.CraftingViews.SavedOrderView;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Saved Craft Orders (spec section 24): personal presets per network. Listing and deleting need only access to
 * the network; saving and editing need {@code SUBMIT_CRAFT}, since a preset is only good for crafting.
 */
public interface SavedOrderService {

    /**
     * @param resourceId text form of the resource; ignored by {@link #update}, a preset keeps its resource
     * @param cpuId      preferred CPU, or {@code null}/blank for automatic
     */
    record SavedOrderInput(String name, String resourceId, Long amount, String cpuId, String notes) {
    }

    CompletionStage<SavedOrderList> list(Session session, UUID networkId, String locale);

    /**
     * Fails with {@code VALIDATION_FAILED}, {@code RESOURCE_NOT_FOUND} when the network neither stores nor can craft the
     * resource, or {@code CONFLICT} beyond {@link SavedOrder#MAX_PER_PLAYER}.
     */
    CompletionStage<SavedOrderView> create(Session session, UUID networkId, SavedOrderInput input, String locale);

    CompletionStage<SavedOrderView> update(Session session, UUID networkId, UUID savedOrderId, SavedOrderInput input,
                                           String locale);

    CompletionStage<Void> delete(Session session, UUID networkId, UUID savedOrderId);
}
