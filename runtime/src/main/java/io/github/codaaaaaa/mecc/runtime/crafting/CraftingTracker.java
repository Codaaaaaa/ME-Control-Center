package io.github.codaaaaaa.mecc.runtime.crafting;

import io.github.codaaaaaa.mecc.core.crafting.CraftingOrder;
import io.github.codaaaaaa.mecc.core.crafting.OrderEvent;
import io.github.codaaaaaa.mecc.core.crafting.OrderEventType;
import io.github.codaaaaaa.mecc.core.crafting.OrderState;
import io.github.codaaaaaa.mecc.core.live.LiveEvent;
import io.github.codaaaaaa.mecc.core.persistence.DataStore;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform.CpuCapture;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform.CpuState;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform.JobFate;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform.JobState;
import io.github.codaaaaaa.mecc.runtime.networks.NetworkGuard;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Follows every active crafting order until it ends (spec sections 10-11).
 *
 * <p>Every {@link #POLL_INTERVAL} it reads the CPUs of each network that has active orders or live viewers,
 * through {@link CpuSnapshots}, so the server-thread cost is one CPU capture per network however many orders
 * and browsers there are. The platform reports what became of each tracked job; the tracker turns that into
 * order state, conservatively:
 * <ul>
 *   <li>a job whose CPU is unloaded or disconnected stays {@code RUNNING} (unobserved): it may come back;</li>
 *   <li>only after {@link #LOST_AFTER} without a sighting is it given up as {@code UNKNOWN};</li>
 *   <li>a job whose CPU cannot tell completion from cancellation ends as {@code UNKNOWN}, never a guess.</li>
 * </ul>
 */
public final class CraftingTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger(CraftingTracker.class);
    static final Duration POLL_INTERVAL = Duration.ofSeconds(2);
    static final Duration LOST_AFTER = Duration.ofHours(24);
    /** A running order's last sighting is persisted at most this often. */
    static final Duration PERSIST_OBSERVATION_INTERVAL = Duration.ofMinutes(1);

    /** Receives changes; called on ME Control Center worker threads. */
    public interface Listener {
        void orderChanged(CraftingOrder order, String eventType);

        void cpusCaptured(UUID networkId, CpuCapture capture);
    }

    /** Live view of one active order. */
    public static final class Tracked {
        private volatile CraftingOrder order;
        private volatile JobState job;
        private volatile boolean observed;
        private volatile Instant persistedObservation;
        private final AtomicBoolean ending = new AtomicBoolean();

        private Tracked(CraftingOrder order) {
            this.order = order;
            this.persistedObservation = order.lastObservedAt();
        }

        public CraftingOrder order() {
            return order;
        }

        /** The job as last seen on a CPU, or {@code null}. */
        public JobState job() {
            return job;
        }

        /** Whether the job is visible on a loaded CPU right now. */
        public boolean observed() {
            return observed;
        }
    }

    private final DataStore store;
    private final NetworkGuard guard;
    private final ResourceLabels labels;
    private final Clock clock;
    private final Map<UUID, Tracked> active = new ConcurrentHashMap<>();
    private volatile CpuSnapshots snapshots;
    private volatile Listener listener = new Listener() {
        @Override
        public void orderChanged(CraftingOrder order, String eventType) {
        }

        @Override
        public void cpusCaptured(UUID networkId, CpuCapture capture) {
        }
    };
    private volatile Supplier<Set<UUID>> viewedNetworks = Set::of;
    private volatile boolean stopped;

    public CraftingTracker(DataStore store, NetworkGuard guard, ResourceLabels labels, Clock clock) {
        this.store = store;
        this.guard = guard;
        this.labels = labels;
        this.clock = clock;
    }

    public void connect(CpuSnapshots cpuSnapshots, Listener changes, Supplier<Set<UUID>> networksWithViewers) {
        this.snapshots = cpuSnapshots;
        this.listener = changes;
        this.viewedNetworks = networksWithViewers;
        cpuSnapshots.onCapture(this::apply);
    }

    /** Loads active orders and starts polling. */
    public CompletableFuture<Void> start(ScheduledExecutorService scheduler) {
        return store.read(repos -> repos.orders().listByStates(OrderState.ACTIVE)).thenAccept(orders -> {
            for (CraftingOrder order : orders) {
                if (order.state() == OrderState.SUBMITTING) {
                    // ME Control Center stopped between recording the order and hearing back from the crafting system.
                    end(new Tracked(order), OrderState.UNKNOWN, "INTERRUPTED",
                            "ME Control Center stopped while this order was being submitted. Check the crafting CPUs in game.", null);
                } else if (order.jobId() == null) {
                    end(new Tracked(order), OrderState.UNKNOWN, "TRACKING_LOST",
                            "This crafting CPU type cannot be followed across a server restart.", null);
                } else {
                    active.put(order.id(), new Tracked(order));
                }
            }
            if (!orders.isEmpty()) {
                LOGGER.info("ME Control Center is tracking {} active crafting order(s)", active.size());
            }
            scheduler.execute(() -> runPeriodic(scheduler));
        });
    }

    public void stop() {
        stopped = true;
    }

    private void runPeriodic(ScheduledExecutorService scheduler) {
        if (stopped) {
            return;
        }
        pollOnce().whenComplete((ignored, error) -> {
            if (error != null) {
                LOGGER.debug("ME Control Center crafting poll failed: {}", error.toString());
            }
            if (!stopped && !scheduler.isShutdown()) {
                try {
                    scheduler.schedule(() -> runPeriodic(scheduler), POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException e) {
                    // The runtime is stopping.
                }
            }
        });
    }

    /** Reads the CPUs of every network that has active orders or live viewers. */
    public CompletableFuture<Void> pollOnce() {
        CpuSnapshots cpuSnapshots = snapshots;
        if (cpuSnapshots == null) {
            return CompletableFuture.completedFuture(null);
        }
        Set<UUID> networks = new HashSet<>(viewedNetworks.get());
        active.values().forEach(tracked -> networks.add(tracked.order.networkId()));
        List<CompletableFuture<?>> reads = new ArrayList<>();
        for (UUID networkId : networks) {
            guard.onlineGridKey(networkId).ifPresent(gridKey -> reads.add(cpuSnapshots.latest(networkId, gridKey)
                    .exceptionally(error -> {
                        LOGGER.debug("Could not read crafting CPUs of network {}: {}", networkId, error.toString());
                        return null;
                    })));
        }
        return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new));
    }

    public void track(CraftingOrder order) {
        if (order.state().active()) {
            active.put(order.id(), new Tracked(order));
        }
    }

    public Optional<Tracked> find(UUID orderId) {
        return Optional.ofNullable(active.get(orderId));
    }

    /** The active order behind a CPU's job, if ME Control Center started it. */
    public Optional<Tracked> byJob(UUID networkId, String cpuId, JobState job) {
        if (job == null) {
            return Optional.empty();
        }
        for (Tracked tracked : active.values()) {
            CraftingOrder order = tracked.order;
            if (!order.networkId().equals(networkId)) {
                continue;
            }
            if (job.jobId() != null ? job.jobId().equals(order.jobId())
                    : order.jobId() == null && Objects.equals(cpuId, order.cpuId())
                    && job.output().id().equals(order.target().resourceId())) {
                return Optional.of(tracked);
            }
        }
        return Optional.empty();
    }

    public Set<String> watchedJobs(UUID networkId) {
        Set<String> jobs = new HashSet<>();
        for (Tracked tracked : active.values()) {
            if (tracked.order.networkId().equals(networkId) && tracked.order.jobId() != null) {
                jobs.add(tracked.order.jobId());
            }
        }
        return jobs;
    }

    /** Applies a CPU capture to the network's active orders. */
    void apply(UUID networkId, CpuCapture capture) {
        Instant now = clock.instant();
        Map<String, CpuState> cpusById = new HashMap<>();
        Map<String, CpuState> cpusByJob = new HashMap<>();
        for (CpuState cpu : capture.cpus()) {
            cpusById.put(cpu.id(), cpu);
            if (cpu.job() != null && cpu.job().jobId() != null) {
                cpusByJob.put(cpu.job().jobId(), cpu);
            }
        }
        for (Tracked tracked : active.values()) {
            CraftingOrder order = tracked.order;
            if (!order.networkId().equals(networkId) || order.state() != OrderState.RUNNING) {
                continue;
            }
            if (order.jobId() != null) {
                JobFate fate = capture.watched().getOrDefault(order.jobId(), JobFate.NOT_FOUND);
                CpuState cpu = cpusByJob.get(order.jobId());
                if (fate == JobFate.RUNNING || cpu != null) {
                    seen(tracked, cpu, now);
                } else if (fate == JobFate.COMPLETED) {
                    end(tracked, OrderState.COMPLETED, null, null, null);
                } else if (fate == JobFate.CANCELLED) {
                    end(tracked, OrderState.CANCELLED, null, "Cancelled in game or by the crafting system.", null);
                } else {
                    unseen(tracked, now);
                }
            } else {
                CpuState cpu = cpusById.get(order.cpuId());
                if (cpu == null) {
                    unseen(tracked, now);
                } else if (cpu.job() != null && cpu.job().output().id().equals(order.target().resourceId())) {
                    seen(tracked, cpu, now);
                } else {
                    end(tracked, OrderState.UNKNOWN, "OUTCOME_NOT_REPORTED",
                            "The job ended, but this crafting CPU type does not report whether it completed.", null);
                }
            }
        }
        listener.cpusCaptured(networkId, capture);
    }

    private void seen(Tracked tracked, CpuState cpu, Instant now) {
        CraftingOrder order = tracked.order;
        JobState job = cpu == null ? tracked.job : cpu.job();
        tracked.job = job;
        tracked.observed = cpu != null;
        if (cpu == null) {
            return;
        }
        Double percent = job == null || job.progress() == null ? order.progressPercent() : job.progress() * 100;
        boolean moved = !cpu.id().equals(order.cpuId());
        String jobId = job != null && job.jobId() != null ? job.jobId() : order.jobId();
        CraftingOrder updated = order.observed(jobId, cpu.id(), labels.text(cpu.name(), "en_us"), percent, now);
        tracked.order = updated;
        Instant persisted = tracked.persistedObservation;
        if (moved || persisted == null || Duration.between(persisted, now).compareTo(PERSIST_OBSERVATION_INTERVAL) >= 0) {
            tracked.persistedObservation = now;
            store.write(repos -> repos.orders().update(updated)).thenAccept(exists -> {
                if (!exists) {
                    active.remove(updated.id()); // The network record was deleted.
                }
            }).exceptionally(error -> {
                LOGGER.warn("Could not record progress of crafting order {}", updated.id(), error);
                return null;
            });
        }
    }

    private void unseen(Tracked tracked, Instant now) {
        tracked.observed = false;
        CraftingOrder order = tracked.order;
        Instant lastSeen = order.lastObservedAt() != null ? order.lastObservedAt() : order.startedAt();
        if (lastSeen == null || Duration.between(lastSeen, now).compareTo(LOST_AFTER) > 0) {
            end(tracked, OrderState.UNKNOWN, "JOB_LOST",
                    "The job has not been seen on any crafting CPU for a long time; its CPU may have been removed.", null);
        }
    }

    /**
     * Moves an active order to a final state, once: concurrent callers (the poll loop and a user's cancel)
     * never both succeed.
     *
     * @param actor player who caused it, or {@code null}
     * @return the stored order, or empty when another caller ended it first
     */
    public CompletableFuture<Optional<CraftingOrder>> finish(UUID orderId, OrderState state, String code, String message,
                                                             UUID actor) {
        Tracked tracked = active.get(orderId);
        if (tracked == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return end(tracked, state, code, message, actor);
    }

    private CompletableFuture<Optional<CraftingOrder>> end(Tracked tracked, OrderState state, String code, String message,
                                                          UUID actor) {
        if (!tracked.ending.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        CraftingOrder ended = tracked.order.ended(state, clock.instant(), code, message);
        OrderEventType type = switch (state) {
            case COMPLETED -> OrderEventType.COMPLETED;
            case CANCELLED -> OrderEventType.CANCELLED;
            default -> OrderEventType.UNKNOWN;
        };
        Map<String, String> details = code == null ? Map.of() : Map.of("code", code);
        return store.write(repos -> {
                    boolean exists = repos.orders().update(ended);
                    if (exists) {
                        repos.orders().appendEvent(new OrderEvent(ended.id(), ended.endedAt(), type, actor, details));
                    }
                    return exists;
                })
                .handle((exists, error) -> {
                    active.remove(ended.id());
                    if (error != null) {
                        LOGGER.warn("Could not record the end of crafting order {}", ended.id(), error);
                        return Optional.<CraftingOrder>empty();
                    }
                    tracked.order = ended;
                    if (Boolean.TRUE.equals(exists)) {
                        LOGGER.debug("Crafting order {} ended: {}", ended.id(), state);
                        listener.orderChanged(ended, switch (state) {
                            case COMPLETED -> LiveEvent.ORDER_COMPLETED;
                            case FAILED, UNKNOWN -> LiveEvent.ORDER_FAILED;
                            default -> LiveEvent.ORDER_UPDATED;
                        });
                        return Optional.of(ended);
                    }
                    return Optional.<CraftingOrder>empty();
                });
    }
}
