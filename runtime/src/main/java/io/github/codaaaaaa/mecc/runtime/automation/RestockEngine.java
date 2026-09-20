package io.github.codaaaaaa.mecc.runtime.automation;

import io.github.codaaaaaa.mecc.core.audit.AuditAction;
import io.github.codaaaaaa.mecc.core.audit.AuditEvent;
import io.github.codaaaaaa.mecc.core.audit.AuditEvent.AuditResult;
import io.github.codaaaaaa.mecc.core.automation.RestockRule;
import io.github.codaaaaaa.mecc.core.config.AutomationConfig;
import io.github.codaaaaaa.mecc.core.crafting.CraftingOrder;
import io.github.codaaaaaa.mecc.core.crafting.OrderFilter;
import io.github.codaaaaaa.mecc.core.crafting.OrderSource;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import io.github.codaaaaaa.mecc.core.permissions.NetworkAccess;
import io.github.codaaaaaa.mecc.core.permissions.NetworkCapability;
import io.github.codaaaaaa.mecc.core.persistence.DataStore;
import io.github.codaaaaaa.mecc.core.resources.ResourceIndex;
import io.github.codaaaaaa.mecc.core.users.PlayerProfile;
import io.github.codaaaaaa.mecc.core.users.WebUser;
import io.github.codaaaaaa.mecc.runtime.crafting.DefaultCraftingService;
import io.github.codaaaaaa.mecc.runtime.networks.NetworkGuard;
import io.github.codaaaaaa.mecc.runtime.resources.ResourceSnapshots;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Auto Restock / Keep Stock (spec section 25). Every interval the enabled rules are compared against the storage
 * snapshots the terminal already keeps; a resource below its minimum is crafted back up to its target.
 *
 * <p>Automation is opt-in and deliberately timid. Before any rule submits:
 * <ul>
 *   <li>{@code automation.auto_restock_enabled} must be on, and the rule itself enabled;</li>
 *   <li>its owner must still hold {@code MANAGE_AUTOMATION} on the network, or the rule is turned off;</li>
 *   <li>the rule must be past its cooldown and out of failure backoff;</li>
 *   <li>the resource must not already be crafting, and no order of this network may be running for it;</li>
 *   <li>the network must be under {@code max_active_jobs_per_network} automation jobs.</li>
 * </ul>
 * Every submission is audited as {@code RESTOCK_CRAFT}, a failure backs the rule off exponentially, and
 * {@link RestockRule#MAX_FAILURES} failures in a row turn it off.
 */
public final class RestockEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(RestockEngine.class);
    /**
     * Active orders read per network to find out what is already being crafted.
     *
     * <p>ponytail: one page, not the whole table. Beyond this many active orders on one network a rule could
     * queue a second job for a resource; the crafting system's own "already crafting" flag still catches the
     * common case. Count in SQL if a network ever runs that many jobs at once.
     */
    private static final int ACTIVE_ORDER_PAGE = 200;

    private final DataStore store;
    private final NetworkGuard guard;
    private final ResourceSnapshots snapshots;
    private final DefaultCraftingService crafting;
    private final AutomationConfig config;
    private final boolean adminOverride;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();

    public RestockEngine(DataStore store, NetworkGuard guard, ResourceSnapshots snapshots,
                         DefaultCraftingService crafting, AutomationConfig config, boolean adminOverride, Clock clock) {
        this.store = store;
        this.guard = guard;
        this.snapshots = snapshots;
        this.crafting = crafting;
        this.config = config;
        this.adminOverride = adminOverride;
        this.clock = clock;
    }

    public void start(ScheduledExecutorService scheduler) {
        long interval = config.checkIntervalSeconds();
        scheduler.scheduleWithFixedDelay(() -> runOnce().exceptionally(error -> {
            LOGGER.debug("ME Control Center could not check restock rules: {}", error.toString());
            return 0;
        }), interval, interval, TimeUnit.SECONDS);
    }

    /** What one network needs for its rules: current storage, and what is already being crafted there. */
    private record NetworkState(ResourceIndex storage, Set<String> crafting, int activeAutomationJobs) {
    }

    /** Checks every enabled rule once and returns how many crafting jobs were submitted. For tests and diagnostics. */
    public CompletableFuture<Integer> runOnce() {
        if (!config.autoRestockEnabled() || !running.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(0);
        }
        return store.read(repos -> repos.restock().enabledRules())
                .thenCompose(rules -> {
                    Map<UUID, List<RestockRule>> byNetwork = new LinkedHashMap<>();
                    rules.forEach(rule -> byNetwork.computeIfAbsent(rule.networkId(), id -> new ArrayList<>()).add(rule));
                    List<CompletableFuture<Integer>> networks = new ArrayList<>();
                    byNetwork.forEach((networkId, networkRules) -> networks.add(runNetwork(networkId, networkRules)));
                    return CompletableFuture.allOf(networks.toArray(CompletableFuture[]::new))
                            .thenApply(ignored -> networks.stream().mapToInt(CompletableFuture::join).sum());
                })
                .whenComplete((ignored, error) -> running.set(false));
    }

    private CompletableFuture<Integer> runNetwork(UUID networkId, List<RestockRule> rules) {
        Optional<String> gridKey = guard.onlineGridKey(networkId);
        if (gridKey.isEmpty()) {
            // Offline or ambiguous: no stock to judge, so nothing is submitted and no rule is penalised.
            return CompletableFuture.completedFuture(0);
        }
        return state(networkId, gridKey.get())
                .thenCompose(state -> state == null
                        ? CompletableFuture.completedFuture(0)
                        : submitDue(networkId, rules, state))
                .exceptionally(error -> {
                    LOGGER.debug("ME Control Center could not restock network {}: {}", networkId, error.toString());
                    return 0;
                });
    }

    private CompletableFuture<NetworkState> state(UUID networkId, String gridKey) {
        return snapshots.latest(networkId, gridKey)
                .thenCompose(snapshot -> store.read(repos ->
                                repos.orders().list(networkId, OrderFilter.ACTIVE.states(), ACTIVE_ORDER_PAGE, null))
                        .thenApply(active -> {
                            Set<String> busy = new HashSet<>();
                            int automation = 0;
                            for (CraftingOrder order : active) {
                                busy.add(order.target().resourceId().toString());
                                if (order.source() == OrderSource.AUTOMATION) {
                                    automation++;
                                }
                            }
                            return new NetworkState(snapshot.index(), busy, automation);
                        }))
                .exceptionally(error -> null);
    }

    /** Submits one job per due rule, in order, so the per-network job limit is respected. */
    private CompletableFuture<Integer> submitDue(UUID networkId, List<RestockRule> rules, NetworkState state) {
        Instant now = clock.instant();
        CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
        int budget = config.maxActiveJobsPerNetwork() - state.activeAutomationJobs();
        for (RestockRule rule : rules) {
            if (budget <= 0) {
                break;
            }
            long deficit = deficit(rule, state, now);
            if (deficit <= 0) {
                continue;
            }
            budget--;
            chain = chain.thenCompose(submitted -> submit(networkId, rule, deficit)
                    .thenApply(success -> submitted + (success ? 1 : 0)));
        }
        return chain;
    }

    /** How much to craft for this rule right now, or {@code 0} when it must not run. */
    private static long deficit(RestockRule rule, NetworkState state, Instant now) {
        if (!rule.ready(now) || state.storage() == null) {
            return 0;
        }
        // A resource already being crafted (by anyone, including in game) is never queued again.
        if (state.crafting().contains(rule.resource().resourceId().toString())) {
            return 0;
        }
        int position = state.storage().indexOf(rule.resource().resourceId());
        if (position >= 0 && state.storage().craftingAmount(position) != ResourceIndex.NOT_CRAFTING) {
            return 0;
        }
        long stored = position < 0 ? 0 : state.storage().amount(position);
        return stored < rule.minimum() ? rule.restockTo() - stored : 0;
    }

    private CompletableFuture<Boolean> submit(UUID networkId, RestockRule rule, long amount) {
        return owner(networkId, rule).thenCompose(owner -> {
            if (owner == null) {
                return CompletableFuture.completedFuture(false);
            }
            return crafting.submitAutomatic(networkId, owner, rule.resource().resourceId(), amount, rule.cpuId())
                    .handle((order, error) -> error == null
                            ? succeeded(rule, order)
                            : failed(networkId, rule, amount, error))
                    .thenCompose(done -> done);
        });
    }

    /**
     * The player the rule crafts as, or {@code null} when they may no longer manage automation on this network;
     * the rule is then turned off instead of running with permissions its owner has lost.
     */
    private CompletableFuture<PlayerProfile> owner(UUID networkId, RestockRule rule) {
        return store.write(repos -> {
            WebUser user = repos.users().find(rule.createdBy()).orElse(null);
            NetworkAccess access = NetworkAccess.resolve(
                            repos.networks().memberRole(networkId, rule.createdBy()).orElse(null), false, adminOverride)
                    .orElse(null);
            if (user == null || access == null || !access.allows(NetworkCapability.MANAGE_AUTOMATION)) {
                repos.restock().update(disabled(rule, "PERMISSION_DENIED"));
                repos.audit().append(new AuditEvent(clock.instant(), rule.createdBy(), null, networkId,
                        AuditAction.AUTOMATION_STOPPED, rule.id().toString(), AuditResult.DENIED, false,
                        Map.of("reason", "PERMISSION_DENIED", "resource", rule.resource().resourceId().toString())));
                LOGGER.info("ME Control Center disabled a restock rule: its owner may no longer manage automation on "
                        + "this network");
                return null;
            }
            return new PlayerProfile(user.playerUuid(), user.playerName());
        });
    }

    private CompletableFuture<Boolean> succeeded(RestockRule rule, CraftingOrder order) {
        return store.write(repos -> {
            repos.restock().update(rule.submitted(clock.instant(), order.id()));
            return true;
        });
    }

    private CompletableFuture<Boolean> failed(UUID networkId, RestockRule rule, long amount, Throwable error) {
        String code = codeOf(error);
        RestockRule backedOff = rule.failed(clock.instant(), code);
        return store.write(repos -> {
            repos.restock().update(backedOff);
            repos.audit().append(new AuditEvent(clock.instant(), rule.createdBy(), null, networkId,
                    AuditAction.RESTOCK_CRAFT, rule.id().toString(), AuditResult.FAILED, false,
                    Map.of("resource", rule.resource().resourceId().toString(), "amount", Long.toString(amount),
                            "error", code, "failures", Integer.toString(backedOff.failures()))));
            return false;
        });
    }

    private static RestockRule disabled(RestockRule rule, String reason) {
        return new RestockRule(rule.id(), rule.networkId(), rule.createdBy(), rule.resource(), rule.minimum(),
                rule.restockTo(), rule.cpuId(), rule.cooldown(), false, rule.lastRunAt(), rule.lastOrderId(),
                rule.failures(), rule.pausedUntil(), reason, rule.createdAt());
    }

    private static String codeOf(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause instanceof MeccException mecc ? mecc.code().name() : "INTERNAL_ERROR";
    }
}
