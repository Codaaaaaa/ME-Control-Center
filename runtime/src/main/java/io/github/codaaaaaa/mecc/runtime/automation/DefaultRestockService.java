package io.github.codaaaaaa.mecc.runtime.automation;

import io.github.codaaaaaa.mecc.core.audit.AuditAction;
import io.github.codaaaaaa.mecc.core.audit.AuditEvent;
import io.github.codaaaaaa.mecc.core.audit.AuditEvent.AuditResult;
import io.github.codaaaaaa.mecc.core.auth.AuthViews.UserView;
import io.github.codaaaaaa.mecc.core.auth.Session;
import io.github.codaaaaaa.mecc.core.automation.RestockRule;
import io.github.codaaaaaa.mecc.core.automation.RestockService;
import io.github.codaaaaaa.mecc.core.automation.RestockViews.RequesterList;
import io.github.codaaaaaa.mecc.core.automation.RestockViews.RequesterRequestView;
import io.github.codaaaaaa.mecc.core.automation.RestockViews.RequesterView;
import io.github.codaaaaaa.mecc.core.automation.RestockViews.RuleList;
import io.github.codaaaaaa.mecc.core.automation.RestockViews.RuleView;
import io.github.codaaaaaa.mecc.core.config.AutomationConfig;
import io.github.codaaaaaa.mecc.core.config.CraftingConfig;
import io.github.codaaaaaa.mecc.core.error.ErrorCode;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import io.github.codaaaaaa.mecc.core.permissions.NetworkAccess;
import io.github.codaaaaaa.mecc.core.permissions.NetworkCapability;
import io.github.codaaaaaa.mecc.core.persistence.DataStore;
import io.github.codaaaaaa.mecc.core.persistence.DuplicateKeyException;
import io.github.codaaaaaa.mecc.core.persistence.Repositories;
import io.github.codaaaaaa.mecc.core.resources.ResourceId;
import io.github.codaaaaaa.mecc.core.resources.ResourceIndex;
import io.github.codaaaaaa.mecc.platform.NetworkPlatform;
import io.github.codaaaaaa.mecc.platform.NetworkPlatform.RequesterCapture;
import io.github.codaaaaaa.mecc.platform.thread.ServerThreadGateway;
import io.github.codaaaaaa.mecc.runtime.crafting.ResourceLabels;
import io.github.codaaaaaa.mecc.runtime.crafting.UserCache;
import io.github.codaaaaaa.mecc.runtime.networks.NetworkGuard;
import io.github.codaaaaaa.mecc.runtime.resources.ResourceResolver;
import io.github.codaaaaaa.mecc.runtime.resources.ResourceSnapshots;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Keep Stock rules (spec section 25). Rules belong to the network: any Manager may see, change, and stop them,
 * because they spend the network's resources and everyone on it sees the crafting jobs.
 */
public final class DefaultRestockService implements RestockService {
    /** Reading or changing in-game requesters waits this long for the server thread. */
    static final Duration SERVER_CALL_TIMEOUT = Duration.ofSeconds(5);

    private final DataStore store;
    private final NetworkGuard guard;
    private final NetworkPlatform networks;
    private final ServerThreadGateway gateway;
    private final ResourceResolver resolver;
    private final ResourceSnapshots snapshots;
    private final ResourceLabels labels;
    private final UserCache users;
    private final Supplier<String> assetVersion;
    private final AutomationConfig config;
    private final CraftingConfig crafting;
    private final Clock clock;

    public DefaultRestockService(DataStore store, NetworkGuard guard, NetworkPlatform networks,
                                 ServerThreadGateway gateway, ResourceResolver resolver, ResourceSnapshots snapshots,
                                 ResourceLabels labels, UserCache users, Supplier<String> assetVersion,
                                 AutomationConfig config, CraftingConfig crafting, Clock clock) {
        this.store = store;
        this.guard = guard;
        this.networks = networks;
        this.gateway = gateway;
        this.resolver = resolver;
        this.snapshots = snapshots;
        this.labels = labels;
        this.users = users;
        this.assetVersion = assetVersion;
        this.config = config;
        this.crafting = crafting;
        this.clock = clock;
    }

    @Override
    public CompletionStage<RuleList> rules(Session session, UUID networkId, String locale) {
        return guard.access(session, networkId)
                .thenCompose(access -> {
                    NetworkGuard.require(access, NetworkCapability.VIEW_NETWORK);
                    return store.read(repos -> repos.restock().list(networkId));
                })
                .thenCompose(rules -> list(networkId, rules, locale));
    }

    @Override
    public CompletionStage<RuleView> createRule(Session session, UUID networkId, RuleInput input, String locale) {
        Fields fields;
        ResourceId resource;
        try {
            fields = fields(input, null);
            resource = ResourceId.parse(input.resourceId() == null ? "" : input.resourceId().strip())
                    .orElseThrow(() -> MeccException.validation("resourceId", "Not a valid resource ID"));
        } catch (MeccException e) {
            return CompletableFuture.failedFuture(e);
        }
        return guard.access(session, networkId)
                .thenCompose(access -> {
                    NetworkGuard.require(access, NetworkCapability.MANAGE_AUTOMATION);
                    return resolver.resolve(networkId, resource).thenApply(descriptor -> Map.entry(access, descriptor));
                })
                .thenCompose(resolved -> {
                    Instant now = clock.instant();
                    RestockRule created = new RestockRule(UUID.randomUUID(), networkId, session.user().playerUuid(),
                            labels.target(resolved.getValue()), fields.minimum, fields.restockTo, fields.cpuId,
                            fields.cooldown, fields.enabled, null, null, 0, null, null, now);
                    return store.write(repos -> {
                        if (repos.restock().countByNetwork(networkId) >= config.maxRulesPerNetwork()) {
                            throw new MeccException(ErrorCode.CONFLICT, "This network has too many restock rules",
                                    Map.of("limit", config.maxRulesPerNetwork()));
                        }
                        try {
                            repos.restock().insert(created);
                        } catch (DuplicateKeyException e) {
                            throw new MeccException(ErrorCode.CONFLICT, "This resource already has a restock rule");
                        }
                        audit(repos, session, networkId, created, AuditAction.RESTOCK_RULE_CHANGE, "created",
                                resolved.getKey());
                        return created;
                    });
                })
                .thenCompose(rule -> view(networkId, rule, locale));
    }

    @Override
    public CompletionStage<RuleView> updateRule(Session session, UUID networkId, UUID ruleId, RuleInput input,
                                                String locale) {
        return guard.access(session, networkId)
                .thenCompose(access -> {
                    NetworkGuard.require(access, NetworkCapability.MANAGE_AUTOMATION);
                    return store.write(repos -> {
                        RestockRule existing = of(repos.restock().find(ruleId).orElse(null), networkId);
                        Fields fields = fields(input, existing);
                        // Editing a rule clears its failure backoff: the admin has had a chance to fix the cause.
                        RestockRule updated = new RestockRule(existing.id(), networkId, existing.createdBy(),
                                existing.resource(), fields.minimum, fields.restockTo, fields.cpuId, fields.cooldown,
                                fields.enabled, existing.lastRunAt(), existing.lastOrderId(), 0, null, null,
                                existing.createdAt());
                        repos.restock().update(updated);
                        audit(repos, session, networkId, updated, AuditAction.RESTOCK_RULE_CHANGE,
                                fields.enabled ? "enabled" : "disabled", access);
                        return updated;
                    });
                })
                .thenCompose(rule -> view(networkId, rule, locale));
    }

    @Override
    public CompletionStage<Void> deleteRule(Session session, UUID networkId, UUID ruleId) {
        return guard.access(session, networkId).thenCompose(access -> {
            NetworkGuard.require(access, NetworkCapability.MANAGE_AUTOMATION);
            return store.write(repos -> {
                RestockRule existing = of(repos.restock().find(ruleId).orElse(null), networkId);
                repos.restock().delete(ruleId);
                audit(repos, session, networkId, existing, AuditAction.RESTOCK_RULE_CHANGE, "deleted", access);
                return null;
            });
        });
    }

    @Override
    public CompletionStage<RuleList> stopAll(Session session, UUID networkId, String locale) {
        return guard.access(session, networkId)
                .thenCompose(access -> {
                    NetworkGuard.require(access, NetworkCapability.MANAGE_AUTOMATION);
                    return store.write(repos -> {
                        int stopped = repos.restock().disableAll(networkId);
                        repos.audit().append(new AuditEvent(clock.instant(), session.user().playerUuid(),
                                session.device().id(), networkId, AuditAction.AUTOMATION_STOPPED, "restock",
                                AuditResult.SUCCESS, access.requiresOverride(NetworkCapability.MANAGE_AUTOMATION),
                                Map.of("rules", Integer.toString(stopped))));
                        return repos.restock().list(networkId);
                    });
                })
                .thenCompose(rules -> list(networkId, rules, locale));
    }

    // --- in-game ME Requesters ----------------------------------------------------------------------

    @Override
    public CompletionStage<RequesterList> requesters(Session session, UUID networkId, String locale) {
        return guard.access(session, networkId).thenCompose(access -> {
            NetworkGuard.require(access, NetworkCapability.VIEW_NETWORK);
            Optional<String> gridKey = guard.onlineGridKey(networkId);
            if (gridKey.isEmpty()) {
                // Requesters live in the world: an offline network simply has none to show.
                return CompletableFuture.completedFuture(new RequesterList(List.of(), false, assetVersion.get()));
            }
            return gateway.call("automation.requesters", () -> networks.captureRequesters(gridKey.get()),
                    SERVER_CALL_TIMEOUT).thenApply(capture -> view(capture, locale));
        });
    }

    @Override
    public CompletionStage<Void> deleteRequest(Session session, UUID networkId, String requesterId, int slot) {
        String id = requesterId == null ? "" : requesterId.strip();
        if (id.isEmpty() || id.length() > 128) {
            return CompletableFuture.failedFuture(MeccException.validation("requesterId", "Not a valid requester ID"));
        }
        if (slot < 0) {
            return CompletableFuture.failedFuture(MeccException.validation("slot", "slot must be 0 or more"));
        }
        return guard.access(session, networkId).thenCompose(access -> {
            NetworkGuard.require(access, NetworkCapability.MANAGE_AUTOMATION);
            String gridKey = guard.gridKey(networkId);
            return gateway.call("automation.clearRequest", () -> networks.clearRequest(gridKey, id, slot),
                            SERVER_CALL_TIMEOUT)
                    .thenCompose(cleared -> {
                        if (!cleared) {
                            throw new MeccException(ErrorCode.NOT_FOUND,
                                    "No such ME Requester request on this network right now");
                        }
                        return store.write(repos -> {
                            repos.audit().append(new AuditEvent(clock.instant(), session.user().playerUuid(),
                                    session.device().id(), networkId, AuditAction.REQUESTER_REQUEST_CLEARED,
                                    id + "#" + slot, AuditResult.SUCCESS,
                                    access.requiresOverride(NetworkCapability.MANAGE_AUTOMATION), Map.of()));
                            return (Void) null;
                        });
                    });
        });
    }

    private RequesterList view(RequesterCapture capture, String locale) {
        String language = locale == null || locale.isBlank() ? "en_us" : locale;
        return new RequesterList(capture.requesters().stream()
                .map(requester -> new RequesterView(requester.id(),
                        requester.name() == null ? null : labels.text(requester.name(), language),
                        requester.location(), requester.online(), requester.requests().stream()
                        .map(request -> new RequesterRequestView(request.slot(),
                                labels.label(request.resource(), language), request.amount(), request.batch(),
                                request.enabled(), request.status(), request.stored()))
                        .toList()))
                .toList(), capture.supported(), assetVersion.get());
    }

    // --- views --------------------------------------------------------------------------------------

    private CompletableFuture<RuleList> list(UUID networkId, List<RestockRule> rules, String locale) {
        Set<UUID> creators = rules.stream().map(RestockRule::createdBy).collect(Collectors.toSet());
        return stored(networkId, rules).thenCompose(storage -> users.views(creators)
                .thenApply(names -> new RuleList(rules.stream()
                        .map(rule -> view(rule, storage, names.get(rule.createdBy()), locale)).toList(),
                        config.maxRulesPerNetwork(), config.autoRestockEnabled(), config.maxActiveJobsPerNetwork(),
                        assetVersion.get())));
    }

    private CompletableFuture<RuleView> view(UUID networkId, RestockRule rule, String locale) {
        return list(networkId, List.of(rule), locale).thenApply(list -> list.rules().get(0));
    }

    private RuleView view(RestockRule rule, ResourceIndex storage, UserView creator, String locale) {
        Long amount = null;
        if (storage != null) {
            int position = storage.indexOf(rule.resource().resourceId());
            amount = position < 0 ? 0L : storage.amount(position);
        }
        return new RuleView(rule.id(), rule.networkId(), creator == null ? null : creator.playerName(),
                labels.label(rule.resource(), locale == null || locale.isBlank() ? "en_us" : locale), rule.minimum(),
                rule.restockTo(), rule.cpuId(), rule.cooldown(), rule.enabled(), amount, rule.lastRunAt(),
                rule.lastOrderId(), rule.failures(), rule.pausedUntil(), rule.lastError(), rule.createdAt());
    }

    /** Current storage of the network, or {@code null} when it is offline or could not be read. */
    private CompletableFuture<ResourceIndex> stored(UUID networkId, List<RestockRule> rules) {
        Optional<String> gridKey = rules.isEmpty() ? Optional.empty() : guard.onlineGridKey(networkId);
        return gridKey.map(key -> snapshots.latest(networkId, key)
                        .thenApply(ResourceSnapshots.Snapshot::index)
                        .exceptionally(error -> null))
                .orElseGet(() -> CompletableFuture.completedFuture(null));
    }

    // --- validation ---------------------------------------------------------------------------------

    private static RestockRule of(RestockRule rule, UUID networkId) {
        if (rule == null || !rule.networkId().equals(networkId)) {
            throw new MeccException(ErrorCode.NOT_FOUND, "Restock rule not found");
        }
        return rule;
    }

    private record Fields(long minimum, long restockTo, String cpuId, int cooldown, boolean enabled) {
    }

    /** @param existing the rule being changed, or {@code null} when it is being created */
    private Fields fields(RuleInput input, RestockRule existing) {
        if (input == null) {
            throw MeccException.validation("minimum", "minimum is required");
        }
        long minimum = input.minimum() != null ? input.minimum() : existing == null ? -1 : existing.minimum();
        long restockTo = input.restockTo() != null ? input.restockTo() : existing == null ? -1 : existing.restockTo();
        if (minimum < 0) {
            throw MeccException.validation("minimum", "minimum must be 0 or more");
        }
        if (restockTo <= minimum) {
            throw MeccException.validation("restockTo", "The target must be larger than the minimum");
        }
        // The target is also the largest amount one run may ask for (spec section 25: maximum craft amount).
        if (restockTo > crafting.maxCraftAmount()) {
            throw MeccException.validation("restockTo", "The target must be at most " + crafting.maxCraftAmount());
        }
        int cooldown = input.cooldownMinutes() != null ? input.cooldownMinutes()
                : existing == null ? 15 : existing.cooldown();
        if (cooldown < RestockRule.MIN_COOLDOWN_MINUTES || cooldown > RestockRule.MAX_COOLDOWN_MINUTES) {
            throw MeccException.validation("cooldownMinutes", "The cooldown must be between "
                    + RestockRule.MIN_COOLDOWN_MINUTES + " and " + RestockRule.MAX_COOLDOWN_MINUTES + " minutes");
        }
        String cpuId = input.cpuId() == null ? existing == null ? null : existing.cpuId()
                : input.cpuId().isBlank() ? null : input.cpuId().strip();
        if (cpuId != null && cpuId.length() > 128) {
            throw MeccException.validation("cpuId", "Not a valid CPU ID");
        }
        boolean enabled = input.enabled() != null ? input.enabled() : existing != null && existing.enabled();
        return new Fields(minimum, restockTo, cpuId, cooldown, enabled);
    }

    private void audit(Repositories repos, Session session, UUID networkId, RestockRule rule, AuditAction action,
                       String change, NetworkAccess access) {
        repos.audit().append(new AuditEvent(clock.instant(), session.user().playerUuid(), session.device().id(),
                networkId, action, rule.id().toString(), AuditResult.SUCCESS,
                access.requiresOverride(NetworkCapability.MANAGE_AUTOMATION),
                Map.of("resource", rule.resource().resourceId().toString(), "change", change,
                        "minimum", Long.toString(rule.minimum()), "target", Long.toString(rule.restockTo()))));
    }
}
