package io.github.codaaaaaa.mecc.runtime.crafting;

import io.github.codaaaaaa.mecc.core.audit.AuditAction;
import io.github.codaaaaaa.mecc.core.audit.AuditEvent;
import io.github.codaaaaaa.mecc.core.audit.AuditEvent.AuditResult;
import io.github.codaaaaaa.mecc.core.auth.Session;
import io.github.codaaaaaa.mecc.core.config.CraftingConfig;
import io.github.codaaaaaa.mecc.core.crafting.CraftingOrder;
import io.github.codaaaaaa.mecc.core.crafting.CraftingOrderRepository.Cursor;
import io.github.codaaaaaa.mecc.core.crafting.CraftingService;
import io.github.codaaaaaa.mecc.core.crafting.CraftingViews.CpuList;
import io.github.codaaaaaa.mecc.core.crafting.CraftingViews.OrderDetailView;
import io.github.codaaaaaa.mecc.core.crafting.CraftingViews.OrderEventView;
import io.github.codaaaaaa.mecc.core.crafting.CraftingViews.OrderPage;
import io.github.codaaaaaa.mecc.core.crafting.CraftingViews.OrderView;
import io.github.codaaaaaa.mecc.core.crafting.CraftingViews.PlanState;
import io.github.codaaaaaa.mecc.core.crafting.CraftingViews.PlanView;
import io.github.codaaaaaa.mecc.core.crafting.OrderEvent;
import io.github.codaaaaaa.mecc.core.crafting.OrderEventType;
import io.github.codaaaaaa.mecc.core.crafting.OrderFilter;
import io.github.codaaaaaa.mecc.core.crafting.OrderSource;
import io.github.codaaaaaa.mecc.core.crafting.OrderState;
import io.github.codaaaaaa.mecc.core.error.ErrorCode;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import io.github.codaaaaaa.mecc.core.live.LiveEvent;
import io.github.codaaaaaa.mecc.core.permissions.NetworkAccess;
import io.github.codaaaaaa.mecc.core.permissions.NetworkCapability;
import io.github.codaaaaaa.mecc.core.persistence.DataStore;
import io.github.codaaaaaa.mecc.core.persistence.Repositories;
import io.github.codaaaaaa.mecc.core.resources.ResourceId;
import io.github.codaaaaaa.mecc.core.users.PlayerProfile;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform.CancelOutcome;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform.CpuState;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform.SubmitOutcome;
import io.github.codaaaaaa.mecc.platform.thread.ServerThreadGateway;
import io.github.codaaaaaa.mecc.runtime.crafting.CraftingTracker.Tracked;
import io.github.codaaaaaa.mecc.runtime.crafting.PlanStore.Plan;
import io.github.codaaaaaa.mecc.runtime.networks.NetworkGuard;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Remote autocrafting: calculate a plan, confirm it, submit it as a tracked order, cancel it (spec sections
 * 9-12). The server thread only starts calculations, hands over plans, and cancels jobs; waiting, conversion,
 * persistence, and view building run on ME Control Center threads. Every mutation is revalidated on the server thread by
 * the crafting system itself, and every control action is audited.
 */
public final class DefaultCraftingService implements CraftingService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultCraftingService.class);
    static final Duration SERVER_CALL_TIMEOUT = Duration.ofSeconds(5);
    /** How long a calculate request waits before answering {@code CALCULATING}. */
    static final Duration PLAN_WAIT = Duration.ofSeconds(10);
    static final long PLAN_POLL_MILLIS = 100;
    static final int MAX_ORDER_PAGE = 100;

    private final DataStore store;
    private final NetworkGuard guard;
    private final CraftingPlatform crafting;
    private final ServerThreadGateway gateway;
    private final CpuSnapshots cpus;
    private final PlanStore plans;
    private final CraftingTracker tracker;
    private final CraftingPresenter presenter;
    private final ResourceLabels labels;
    private final UserCache users;
    private final ScheduledExecutorService scheduler;
    private final Executor workers;
    private final CraftingConfig config;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private volatile BiConsumer<CraftingOrder, String> orderEvents = (order, type) -> {
    };

    public DefaultCraftingService(DataStore store, NetworkGuard guard, CraftingPlatform crafting, ServerThreadGateway gateway,
                                  CpuSnapshots cpus, PlanStore plans, CraftingTracker tracker, CraftingPresenter presenter,
                                  ResourceLabels labels, UserCache users, ScheduledExecutorService scheduler, Executor workers,
                                  CraftingConfig config, Clock clock) {
        this.store = store;
        this.guard = guard;
        this.crafting = crafting;
        this.gateway = gateway;
        this.cpus = cpus;
        this.plans = plans;
        this.tracker = tracker;
        this.presenter = presenter;
        this.labels = labels;
        this.users = users;
        this.scheduler = scheduler;
        this.workers = workers;
        this.config = config;
        this.clock = clock;
    }

    /** Receives orders created or changed by this service (for live events). */
    public void onOrderEvent(BiConsumer<CraftingOrder, String> listener) {
        this.orderEvents = listener;
    }

    // --- CPUs ---------------------------------------------------------------------------------------

    @Override
    public CompletionStage<CpuList> cpus(Session session, UUID networkId, String locale) {
        return guard.access(session, networkId).thenCompose(access -> {
            NetworkGuard.require(access, NetworkCapability.VIEW_NETWORK);
            String gridKey = guard.gridKey(networkId);
            return cpus.latest(networkId, gridKey).thenCompose(capture ->
                    users.views(presenter.users(networkId, capture)).thenApply(names ->
                            presenter.cpus(networkId, capture, access, uuid(session), names, locale)));
        });
    }

    @Override
    public CompletionStage<Void> cancelCpuJob(Session session, UUID networkId, String cpuId, String jobId) {
        return guard.access(session, networkId).thenCompose(access -> {
            String gridKey = guard.gridKey(networkId);
            return cpus.latest(networkId, gridKey).thenCompose(capture -> {
                CpuState cpu = capture.cpus().stream().filter(candidate -> candidate.id().equals(cpuId)).findFirst()
                        .orElseThrow(() -> new MeccException(ErrorCode.CPU_NOT_FOUND, "No such crafting CPU on this network"));
                if (cpu.job() == null || (jobId != null && !jobId.equals(cpu.job().jobId()))) {
                    throw notRunning();
                }
                Optional<Tracked> order = tracker.byJob(networkId, cpu.id(), cpu.job());
                if (order.isPresent()) {
                    return cancelOrder(session, access, order.get().order(), gridKey).thenApply(ignored -> (Void) null);
                }
                NetworkGuard.require(access, NetworkCapability.CANCEL_ANY_CRAFT);
                ResourceId output = cpu.job().output().id();
                String expectedJob = cpu.job().jobId();
                return gateway.call("crafting.cancel", () -> crafting.cancel(gridKey, cpuId, expectedJob, output),
                                SERVER_CALL_TIMEOUT)
                        .thenCompose(outcome -> {
                            if (outcome != CancelOutcome.CANCELLED) {
                                throw notRunning();
                            }
                            return store.write(repos -> {
                                audit(repos, session, networkId, AuditAction.CRAFT_CANCEL, "cpu:" + cpuId, AuditResult.SUCCESS,
                                        access.requiresOverride(NetworkCapability.CANCEL_ANY_CRAFT),
                                        Map.of("output", output.toString(), "amount", Long.toString(cpu.job().amount())));
                                return (Void) null;
                            });
                        });
            });
        });
    }

    // --- plans --------------------------------------------------------------------------------------

    @Override
    public CompletionStage<PlanView> calculate(Session session, UUID networkId, String resourceId, long amount, String locale) {
        ResourceId resource = ResourceId.parse(resourceId).orElse(null);
        if (resource == null) {
            return CompletableFuture.failedFuture(MeccException.validation("resourceId", "resourceId is not a valid resource ID"));
        }
        if (amount < 1 || amount > config.maxCraftAmount()) {
            return CompletableFuture.failedFuture(new MeccException(ErrorCode.VALIDATION_FAILED,
                    "The amount must be between 1 and " + config.maxCraftAmount(),
                    Map.of("field", "amount", "max", config.maxCraftAmount())));
        }
        PlayerProfile requester = profile(session);
        return guard.liveGrid(session, networkId, NetworkCapability.SUBMIT_CRAFT).thenCompose(gridKey -> {
            plans.checkCapacity(requester.uuid());
            return gateway.call("crafting.calculate",
                            () -> crafting.beginCalculation(gridKey, resource, amount, requester), SERVER_CALL_TIMEOUT)
                    .thenCompose(calculation -> {
                        Plan plan = new Plan(newPlanId(), networkId, requester.uuid(), gridKey, resource, amount,
                                calculation, clock.instant());
                        plans.add(plan);
                        watch(plan, clock.instant().plusSeconds(config.calculationTimeoutSeconds()));
                        return plan.finished.completeOnTimeout(plan, PLAN_WAIT.toMillis(), TimeUnit.MILLISECONDS);
                    })
                    .thenApply(plan -> presenter.plan(plan, locale));
        });
    }

    @Override
    public CompletionStage<PlanView> plan(Session session, UUID networkId, String planId, String locale) {
        return guard.access(session, networkId).thenApply(access -> {
            NetworkGuard.require(access, NetworkCapability.SUBMIT_CRAFT);
            return presenter.plan(findPlan(session, networkId, planId), locale);
        });
    }

    /** Polls the calculation off the server thread; AE2-style calculations offer no completion callback. */
    private void watch(Plan plan, Instant deadline) {
        if (plan.state != PlanState.CALCULATING) {
            return;
        }
        if (plan.calculation.isDone()) {
            CompletableFuture.runAsync(() -> finishCalculation(plan), workers);
            return;
        }
        if (!clock.instant().isBefore(deadline)) {
            plan.calculation.cancel();
            plan.fail(new MeccException(ErrorCode.CRAFT_CALCULATION_FAILED,
                    "The crafting calculation took longer than " + config.calculationTimeoutSeconds() + " seconds"));
            return;
        }
        try {
            scheduler.schedule(() -> watch(plan, deadline), PLAN_POLL_MILLIS, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            plan.calculation.cancel();
            plan.fail(new MeccException(ErrorCode.SERVICE_UNAVAILABLE, "ME Control Center is shutting down"));
        }
    }

    private void finishCalculation(Plan plan) {
        CraftingPlatform.PlanSummary summary;
        try {
            summary = plan.calculation.summary();
        } catch (MeccException e) {
            plan.fail(e);
            return;
        } catch (RuntimeException e) {
            LOGGER.warn("Crafting calculation for {} failed", plan.resource, e);
            plan.fail(new MeccException(ErrorCode.CRAFT_CALCULATION_FAILED, "The crafting calculation failed"));
            return;
        }
        // CPU suitability is shown with the plan, so capture the CPUs now that the storage need is known.
        String gridKey = guard.onlineGridKey(plan.networkId).orElse(plan.gridKey);
        cpus.latest(plan.networkId, gridKey).whenComplete((capture, error) ->
                plan.ready(summary, capture == null ? List.of() : capture.cpus()));
    }

    private Plan findPlan(Session session, UUID networkId, String planId) {
        return plans.find(planId, uuid(session), networkId).orElseThrow(() ->
                new MeccException(ErrorCode.PLAN_NOT_FOUND, "This crafting plan no longer exists. Calculate again."));
    }

    private String newPlanId() {
        byte[] bytes = new byte[12];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    // --- orders -------------------------------------------------------------------------------------

    @Override
    public CompletionStage<OrderView> submit(Session session, UUID networkId, String planId, String cpuId,
                                             OrderSource source, String locale) {
        return guard.access(session, networkId).thenCompose(access -> {
            NetworkGuard.require(access, NetworkCapability.SUBMIT_CRAFT);
            Plan plan = findPlan(session, networkId, planId);
            if (plan.state == PlanState.CALCULATING) {
                throw new MeccException(ErrorCode.PLAN_NOT_READY, "The crafting plan is still being calculated");
            }
            if (plan.state == PlanState.FAILED) {
                throw plan.failure;
            }
            if (!plan.summary.complete()) {
                throw new MeccException(ErrorCode.PLAN_INCOMPLETE,
                        "Some ingredients are missing and cannot be crafted. The job cannot start.");
            }
            String gridKey = guard.gridKey(networkId);
            if (!gridKey.equals(plan.gridKey)) {
                plans.remove(plan);
                throw new MeccException(ErrorCode.PLAN_STALE, "The ME network changed since this plan was calculated. Calculate again.");
            }
            if (!plan.submitting.compareAndSet(false, true)) {
                throw new MeccException(ErrorCode.CONFLICT, "This plan is already being submitted");
            }
            return submitPlan(session, access, plan, gridKey, cpuId, source, locale)
                    .whenComplete((view, error) -> plan.submitting.set(false));
        });
    }

    private CompletableFuture<OrderView> submitPlan(Session session, NetworkAccess access, Plan plan, String gridKey,
                                                    String cpuId, OrderSource source, String locale) {
        Instant now = clock.instant();
        CraftingOrder order = new CraftingOrder(UUID.randomUUID(), plan.networkId, uuid(session), session.device().id(),
                source, labels.target(plan.summary.output()), plan.summary.amount(), OrderState.SUBMITTING,
                null, null, null, plan.summary.bytes(), now, null, null, null, null, null, null);
        PlayerProfile requester = profile(session);
        return store.write(repos -> {
                    repos.orders().insert(order);
                    repos.orders().appendEvent(new OrderEvent(order.id(), now, OrderEventType.CREATED, uuid(session),
                            cpuId == null ? Map.of() : Map.of("cpu", cpuId)));
                    return order;
                })
                .thenCompose(created -> gateway.call("crafting.submit",
                                () -> crafting.submit(gridKey, plan.calculation, cpuId, requester), SERVER_CALL_TIMEOUT)
                        .handle((outcome, error) -> outcome != null ? outcome
                                : SubmitOutcome.rejected(errorCode(error), Map.of())))
                .thenCompose(outcome -> outcome.success()
                        ? started(session, access, plan, order, outcome, locale)
                        : rejected(session, order, outcome));
    }

    private CompletableFuture<OrderView> started(Session session, NetworkAccess access, Plan plan, CraftingOrder order,
                                                 SubmitOutcome outcome, String locale) {
        plans.remove(plan);
        Instant now = clock.instant();
        CraftingOrder running = order.started(outcome.jobId(), outcome.cpuId(), labels.text(outcome.cpuName(), "en_us"), now);
        tracker.track(running);
        return store.write(repos -> {
                    repos.orders().update(running);
                    repos.orders().appendEvent(new OrderEvent(order.id(), now, OrderEventType.STARTED, null,
                            outcome.cpuId() == null ? Map.of() : Map.of("cpu", outcome.cpuId())));
                    audit(repos, session, order.networkId(), AuditAction.CRAFT_SUBMIT, order.id().toString(),
                            AuditResult.SUCCESS, access.requiresOverride(NetworkCapability.SUBMIT_CRAFT), orderParameters(order));
                    return running;
                })
                .thenCompose(stored -> {
                    orderEvents.accept(stored, LiveEvent.ORDER_CREATED);
                    return view(stored, access, session, locale);
                });
    }

    private CompletableFuture<OrderView> rejected(Session session, CraftingOrder order, SubmitOutcome outcome) {
        ErrorCode code = toErrorCode(outcome.errorCode());
        String message = rejectionMessage(code);
        CraftingOrder failed = order.ended(OrderState.FAILED, clock.instant(), code.name(), message);
        return store.write(repos -> {
                    repos.orders().update(failed);
                    repos.orders().appendEvent(new OrderEvent(order.id(), failed.endedAt(), OrderEventType.REJECTED, null,
                            Map.of("code", code.name())));
                    Map<String, String> parameters = new HashMap<>(orderParameters(order));
                    parameters.put("error", code.name());
                    audit(repos, session, order.networkId(), AuditAction.CRAFT_SUBMIT, order.id().toString(),
                            AuditResult.FAILED, false, parameters);
                    return failed;
                })
                .thenApply(stored -> {
                    orderEvents.accept(stored, LiveEvent.ORDER_FAILED);
                    Map<String, Object> details = new HashMap<>(outcome.details());
                    details.put("orderId", order.id().toString());
                    throw new CompletionException(new MeccException(code, message, details));
                });
    }

    @Override
    public CompletionStage<OrderPage> orders(Session session, UUID networkId, OrderFilter filter, int limit, String cursor,
                                             String locale) {
        int pageSize = Math.max(1, Math.min(MAX_ORDER_PAGE, limit));
        Cursor before = cursor == null || cursor.isBlank() ? null : Cursor.decode(cursor)
                .orElseThrow(() -> MeccException.validation("before", "before is not a valid cursor"));
        return guard.access(session, networkId).thenCompose(access -> {
            NetworkGuard.require(access, NetworkCapability.VIEW_NETWORK);
            return store.read(repos -> repos.orders().list(networkId, filter.states(), pageSize + 1, before))
                    .thenCompose(found -> {
                        List<CraftingOrder> page = found.size() > pageSize ? found.subList(0, pageSize) : found;
                        String next = found.size() > pageSize
                                ? new Cursor(page.get(page.size() - 1).createdAt(), page.get(page.size() - 1).id()).encode()
                                : null;
                        return users.views(CraftingPresenter.users(page)).thenApply(names -> new OrderPage(
                                page.stream().map(order -> presenter.order(order, access, uuid(session), names, locale)).toList(),
                                next, presenter.assetVersion()));
                    });
        });
    }

    @Override
    public CompletionStage<OrderDetailView> order(Session session, UUID networkId, UUID orderId, String locale) {
        return guard.access(session, networkId).thenCompose(access -> {
            NetworkGuard.require(access, NetworkCapability.VIEW_NETWORK);
            return store.read(repos -> {
                CraftingOrder order = findOrder(repos, networkId, orderId);
                return Map.entry(order, repos.orders().events(orderId));
            }).thenCompose(entry -> {
                Set<UUID> ids = new java.util.HashSet<>(Set.of(entry.getKey().creatorUuid()));
                entry.getValue().forEach(event -> {
                    if (event.actorUuid() != null) ids.add(event.actorUuid());
                });
                return users.views(ids).thenApply(names -> new OrderDetailView(
                        presenter.order(entry.getKey(), access, uuid(session), names, locale),
                        entry.getValue().stream().map(event -> new OrderEventView(event.at(), event.type(),
                                event.actorUuid() == null ? null : names.get(event.actorUuid()), event.details())).toList(),
                        presenter.assetVersion()));
            });
        });
    }

    @Override
    public CompletionStage<OrderView> cancel(Session session, UUID networkId, UUID orderId, String locale) {
        return guard.access(session, networkId).thenCompose(access -> store.read(repos -> findOrder(repos, networkId, orderId))
                .thenCompose(stored -> {
                    NetworkGuard.require(access, CraftingPresenter.cancelCapability(uuid(session), stored.creatorUuid()));
                    CraftingOrder current = tracker.find(orderId).map(Tracked::order).orElse(stored);
                    if (current.state() != OrderState.RUNNING) {
                        throw notRunning();
                    }
                    return cancelOrder(session, access, current, guard.gridKey(networkId));
                })
                .thenCompose(ended -> view(ended, access, session, locale)));
    }

    /** Cancels a running ME Control Center order on its CPU and records who did it. */
    private CompletableFuture<CraftingOrder> cancelOrder(Session session, NetworkAccess access, CraftingOrder order,
                                                         String gridKey) {
        NetworkCapability capability = CraftingPresenter.cancelCapability(uuid(session), order.creatorUuid());
        NetworkGuard.require(access, capability);
        if (order.cpuId() == null) {
            throw notRunning();
        }
        return gateway.call("crafting.cancel",
                        () -> crafting.cancel(gridKey, order.cpuId(), order.jobId(), order.target().resourceId()),
                        SERVER_CALL_TIMEOUT)
                .thenCompose(outcome -> {
                    if (outcome != CancelOutcome.CANCELLED) {
                        tracker.pollOnce();
                        throw new MeccException(ErrorCode.NOT_RUNNING, outcome == CancelOutcome.CPU_NOT_FOUND
                                ? "The crafting CPU running this order is not loaded right now"
                                : "This order is not running anymore");
                    }
                    return tracker.finish(order.id(), OrderState.CANCELLED, null, null, uuid(session));
                })
                .thenCompose(ended -> store.write(repos -> {
                    audit(repos, session, order.networkId(), AuditAction.CRAFT_CANCEL, order.id().toString(),
                            AuditResult.SUCCESS, access.requiresOverride(capability), orderParameters(order));
                    return ended.orElseGet(() -> repos.orders().find(order.id()).orElse(order));
                }));
    }

    private CompletableFuture<OrderView> view(CraftingOrder order, NetworkAccess access, Session session, String locale) {
        return users.views(Set.of(order.creatorUuid())).thenApply(names -> presenter.order(order, access, uuid(session), names, locale));
    }

    private static CraftingOrder findOrder(Repositories repos, UUID networkId, UUID orderId) {
        return repos.orders().find(orderId)
                .filter(order -> order.networkId().equals(networkId))
                .orElseThrow(() -> new MeccException(ErrorCode.ORDER_NOT_FOUND, "No such crafting order on this network"));
    }

    // --- helpers ------------------------------------------------------------------------------------

    private static ErrorCode toErrorCode(String code) {
        if (code == null) {
            return ErrorCode.CRAFT_REJECTED;
        }
        try {
            return ErrorCode.valueOf(code);
        } catch (IllegalArgumentException e) {
            return ErrorCode.CRAFT_REJECTED;
        }
    }

    private static String errorCode(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof MeccException mecc) {
            return mecc.code().name();
        }
        LOGGER.warn("Crafting submission failed", cause);
        return ErrorCode.INTERNAL_ERROR.name();
    }

    static String rejectionMessage(ErrorCode code) {
        return switch (code) {
            case NO_CRAFTING_CPU -> "This ME network has no crafting CPU.";
            case NO_SUITABLE_CPU -> "No crafting CPU can take this job right now: they are busy, offline, too small, or excluded.";
            case CPU_BUSY -> "The selected crafting CPU is busy.";
            case CPU_OFFLINE -> "The selected crafting CPU is offline.";
            case CPU_TOO_SMALL -> "The selected crafting CPU does not have enough crafting storage for this job.";
            case CPU_NOT_FOUND -> "The selected crafting CPU is no longer part of the network.";
            case MISSING_INGREDIENTS -> "Some ingredients are no longer in storage. Calculate the plan again.";
            case PLAN_INCOMPLETE -> "Some ingredients are missing and cannot be crafted.";
            case NETWORK_OFFLINE -> "The ME network is not loaded right now.";
            case SERVER_THREAD_TIMEOUT, GATEWAY_BUSY -> "The Minecraft server was too busy to start the job. It was not submitted.";
            case SERVER_UNAVAILABLE -> "The Minecraft server is not running. The job was not submitted.";
            default -> "The crafting system refused the job.";
        };
    }

    private static Map<String, String> orderParameters(CraftingOrder order) {
        return Map.of("resource", order.target().resourceId().toString(), "amount", Long.toString(order.amount()));
    }

    private void audit(Repositories repos, Session session, UUID networkId, AuditAction action, String target,
                       AuditResult result, boolean adminOverride, Map<String, String> parameters) {
        repos.audit().append(new AuditEvent(clock.instant(), uuid(session), session.device().id(), networkId, action,
                target, result, adminOverride, parameters));
    }

    private static MeccException notRunning() {
        return new MeccException(ErrorCode.NOT_RUNNING, "This job is not running anymore");
    }

    private static PlayerProfile profile(Session session) {
        return new PlayerProfile(session.user().playerUuid(), session.user().playerName());
    }

    private static UUID uuid(Session session) {
        return session.user().playerUuid();
    }
}
