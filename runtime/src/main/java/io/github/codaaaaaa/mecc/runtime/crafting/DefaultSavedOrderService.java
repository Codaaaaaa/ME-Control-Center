package io.github.codaaaaaa.mecc.runtime.crafting;

import io.github.codaaaaaa.mecc.core.auth.Session;
import io.github.codaaaaaa.mecc.core.config.CraftingConfig;
import io.github.codaaaaaa.mecc.core.crafting.CraftingViews.SavedOrderList;
import io.github.codaaaaaa.mecc.core.crafting.CraftingViews.SavedOrderView;
import io.github.codaaaaaa.mecc.core.crafting.SavedOrder;
import io.github.codaaaaaa.mecc.core.crafting.SavedOrderService;
import io.github.codaaaaaa.mecc.core.error.ErrorCode;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import io.github.codaaaaaa.mecc.core.permissions.NetworkCapability;
import io.github.codaaaaaa.mecc.core.persistence.DataStore;
import io.github.codaaaaaa.mecc.core.resources.ResourceId;
import io.github.codaaaaaa.mecc.runtime.networks.NetworkGuard;
import io.github.codaaaaaa.mecc.runtime.resources.ResourceResolver;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Saved Craft Orders (spec section 24). */
public final class DefaultSavedOrderService implements SavedOrderService {
    private final DataStore store;
    private final NetworkGuard guard;
    private final ResourceResolver resolver;
    private final ResourceLabels labels;
    private final Supplier<String> assetVersion;
    private final CraftingConfig config;
    private final Clock clock;

    public DefaultSavedOrderService(DataStore store, NetworkGuard guard, ResourceResolver resolver, ResourceLabels labels,
                                    Supplier<String> assetVersion, CraftingConfig config, Clock clock) {
        this.store = store;
        this.guard = guard;
        this.resolver = resolver;
        this.labels = labels;
        this.assetVersion = assetVersion;
        this.config = config;
        this.clock = clock;
    }

    @Override
    public CompletionStage<SavedOrderList> list(Session session, UUID networkId, String locale) {
        UUID player = session.user().playerUuid();
        return guard.access(session, networkId)
                .thenCompose(access -> {
                    NetworkGuard.require(access, NetworkCapability.VIEW_NETWORK);
                    return store.read(repos -> repos.savedOrders().list(player, networkId));
                })
                .thenApply(orders -> new SavedOrderList(orders.stream().map(order -> view(order, locale)).toList(),
                        SavedOrder.MAX_PER_PLAYER, assetVersion.get()));
    }

    @Override
    public CompletionStage<SavedOrderView> create(Session session, UUID networkId, SavedOrderInput input, String locale) {
        Fields fields;
        ResourceId resource;
        try {
            fields = fields(input);
            resource = ResourceId.parse(input.resourceId() == null ? "" : input.resourceId().strip())
                    .orElseThrow(() -> MeccException.validation("resourceId", "Not a valid resource ID"));
        } catch (MeccException e) {
            return CompletableFuture.failedFuture(e);
        }
        UUID player = session.user().playerUuid();
        return guard.access(session, networkId)
                .thenCompose(access -> {
                    NetworkGuard.require(access, NetworkCapability.SUBMIT_CRAFT);
                    return resolver.resolve(networkId, resource);
                })
                .thenCompose(descriptor -> {
                    Instant now = clock.instant();
                    SavedOrder created = new SavedOrder(UUID.randomUUID(), player, networkId, fields.name, labels.target(descriptor),
                            fields.amount, fields.cpuId, fields.notes, now, now);
                    return store.write(repos -> {
                        if (repos.savedOrders().countByPlayer(player) >= SavedOrder.MAX_PER_PLAYER) {
                            throw new MeccException(ErrorCode.CONFLICT, "You have too many saved orders",
                                    Map.of("limit", SavedOrder.MAX_PER_PLAYER));
                        }
                        repos.savedOrders().insert(created);
                        return created;
                    });
                })
                .thenApply(order -> view(order, locale));
    }

    @Override
    public CompletionStage<SavedOrderView> update(Session session, UUID networkId, UUID savedOrderId, SavedOrderInput input,
                                                  String locale) {
        Fields fields;
        try {
            fields = fields(input);
        } catch (MeccException e) {
            return CompletableFuture.failedFuture(e);
        }
        UUID player = session.user().playerUuid();
        return guard.access(session, networkId)
                .thenCompose(access -> {
                    NetworkGuard.require(access, NetworkCapability.SUBMIT_CRAFT);
                    return store.write(repos -> {
                        SavedOrder existing = own(repos.savedOrders().find(savedOrderId).orElse(null), player, networkId);
                        SavedOrder updated = new SavedOrder(existing.id(), player, networkId, fields.name, existing.target(),
                                fields.amount, fields.cpuId, fields.notes, existing.createdAt(), clock.instant());
                        repos.savedOrders().update(updated);
                        return updated;
                    });
                })
                .thenApply(order -> view(order, locale));
    }

    @Override
    public CompletionStage<Void> delete(Session session, UUID networkId, UUID savedOrderId) {
        UUID player = session.user().playerUuid();
        return guard.access(session, networkId).thenCompose(access -> {
            NetworkGuard.require(access, NetworkCapability.VIEW_NETWORK);
            return store.write(repos -> {
                own(repos.savedOrders().find(savedOrderId).orElse(null), player, networkId);
                repos.savedOrders().delete(savedOrderId);
                return null;
            });
        });
    }

    private static SavedOrder own(SavedOrder order, UUID player, UUID networkId) {
        if (order == null || !order.playerUuid().equals(player) || !order.networkId().equals(networkId)) {
            throw new MeccException(ErrorCode.NOT_FOUND, "Saved order not found");
        }
        return order;
    }

    private record Fields(String name, long amount, String cpuId, String notes) {
    }

    private Fields fields(SavedOrderInput input) {
        if (input == null) {
            throw MeccException.validation("name", "name is required");
        }
        String name = input.name() == null ? "" : input.name().strip();
        if (name.isEmpty() || name.length() > SavedOrder.MAX_NAME_LENGTH || name.chars().anyMatch(Character::isISOControl)) {
            throw MeccException.validation("name", "The name must be 1-" + SavedOrder.MAX_NAME_LENGTH + " characters");
        }
        Long amount = input.amount();
        if (amount == null || amount < 1 || amount > config.maxCraftAmount()) {
            throw MeccException.validation("amount", "amount must be between 1 and " + config.maxCraftAmount());
        }
        String notes = input.notes() == null ? "" : input.notes().strip();
        if (notes.length() > SavedOrder.MAX_NOTES_LENGTH) {
            throw MeccException.validation("notes", "notes must be at most " + SavedOrder.MAX_NOTES_LENGTH + " characters");
        }
        String cpuId = input.cpuId() == null || input.cpuId().isBlank() ? null : input.cpuId().strip();
        if (cpuId != null && cpuId.length() > 128) {
            throw MeccException.validation("cpuId", "Not a valid CPU ID");
        }
        return new Fields(name, amount, cpuId, notes);
    }

    private SavedOrderView view(SavedOrder order, String locale) {
        return new SavedOrderView(order.id(), order.networkId(), order.name(),
                labels.label(order.target(), locale == null || locale.isBlank() ? "en_us" : locale), order.amount(),
                order.cpuId(), order.notes(), order.createdAt(), order.updatedAt());
    }
}
