package io.github.codaaaaaa.mecc.runtime.crafting;

import io.github.codaaaaaa.mecc.core.crafting.CraftingViews.PlanState;
import io.github.codaaaaaa.mecc.core.error.ErrorCode;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import io.github.codaaaaaa.mecc.core.resources.ResourceId;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform.Calculation;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform.CpuState;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform.PlanSummary;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Crafting plans awaiting confirmation. Kept in memory only: a plan refers to live crafting-system objects
 * that cannot outlive the server anyway. Bounded per user and in total, and expired after {@link #TTL};
 * running calculations of evicted plans are stopped.
 */
public final class PlanStore {
    static final Duration TTL = Duration.ofMinutes(10);
    static final int MAX_PLANS_PER_USER = 4;
    static final int MAX_CALCULATING_PER_USER = 2;
    static final int MAX_CALCULATING = 8;
    static final int MAX_PLANS = 128;

    private final Clock clock;
    private final Map<String, Plan> plans = new LinkedHashMap<>();

    /** A plan and its calculation. Mutable fields are set once by the calculation and read by requests. */
    public static final class Plan {
        final String id;
        final UUID networkId;
        final UUID owner;
        final String gridKey;
        final ResourceId resource;
        final long requestedAmount;
        final Calculation calculation;
        final Instant createdAt;
        final Instant expiresAt;
        final CompletableFuture<Plan> finished = new CompletableFuture<>();
        final AtomicBoolean submitting = new AtomicBoolean();
        volatile PlanState state = PlanState.CALCULATING;
        volatile PlanSummary summary;
        volatile List<CpuState> cpus = List.of();
        volatile MeccException failure;

        Plan(String id, UUID networkId, UUID owner, String gridKey, ResourceId resource, long requestedAmount,
             Calculation calculation, Instant createdAt) {
            this.id = id;
            this.networkId = networkId;
            this.owner = owner;
            this.gridKey = gridKey;
            this.resource = resource;
            this.requestedAmount = requestedAmount;
            this.calculation = calculation;
            this.createdAt = createdAt;
            this.expiresAt = createdAt.plus(TTL);
        }

        void ready(PlanSummary result, List<CpuState> cpuStates) {
            summary = result;
            cpus = List.copyOf(cpuStates);
            state = PlanState.READY;
            finished.complete(this);
        }

        void fail(MeccException error) {
            failure = error;
            state = PlanState.FAILED;
            finished.complete(this);
        }
    }

    public PlanStore(Clock clock) {
        this.clock = clock;
    }

    /** Checks the calculation limits before a new calculation is started. */
    public synchronized void checkCapacity(UUID owner) {
        purgeExpired();
        long mine = plans.values().stream().filter(plan -> plan.owner.equals(owner) && plan.state == PlanState.CALCULATING).count();
        long all = plans.values().stream().filter(plan -> plan.state == PlanState.CALCULATING).count();
        if (mine >= MAX_CALCULATING_PER_USER || all >= MAX_CALCULATING) {
            throw new MeccException(ErrorCode.RATE_LIMITED,
                    "Too many crafting calculations are running. Wait for one to finish and try again.");
        }
    }

    public synchronized void add(Plan plan) {
        purgeExpired();
        List<Plan> own = plans.values().stream().filter(existing -> existing.owner.equals(plan.owner))
                .sorted(Comparator.comparing(existing -> existing.createdAt)).toList();
        for (int i = 0; i <= own.size() - MAX_PLANS_PER_USER; i++) {
            evict(own.get(i));
        }
        while (plans.size() >= MAX_PLANS) {
            evict(plans.values().iterator().next());
        }
        plans.put(plan.id, plan);
    }

    /** The caller's plan on this network, if it still exists. */
    public synchronized Optional<Plan> find(String id, UUID owner, UUID networkId) {
        purgeExpired();
        Plan plan = id == null ? null : plans.get(id);
        if (plan == null || !plan.owner.equals(owner) || !plan.networkId.equals(networkId)) {
            return Optional.empty();
        }
        return Optional.of(plan);
    }

    public synchronized void remove(Plan plan) {
        plans.remove(plan.id, plan);
    }

    public synchronized void purgeExpired() {
        Instant now = clock.instant();
        for (Plan plan : new ArrayList<>(plans.values())) {
            if (!now.isBefore(plan.expiresAt)) {
                evict(plan);
            }
        }
    }

    private void evict(Plan plan) {
        plans.remove(plan.id);
        plan.calculation.cancel();
        if (plan.state == PlanState.CALCULATING) {
            plan.fail(new MeccException(ErrorCode.PLAN_NOT_FOUND, "The crafting plan expired. Calculate again."));
        }
    }

    public synchronized void clear() {
        new ArrayList<>(plans.values()).forEach(this::evict);
    }
}
