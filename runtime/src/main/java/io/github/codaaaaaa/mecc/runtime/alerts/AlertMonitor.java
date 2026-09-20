package io.github.codaaaaaa.mecc.runtime.alerts;

import io.github.codaaaaaa.mecc.core.alerts.AlertEvent;
import io.github.codaaaaaa.mecc.core.alerts.AlertRule;
import io.github.codaaaaaa.mecc.core.alerts.AlertState;
import io.github.codaaaaaa.mecc.core.alerts.AlertType;
import io.github.codaaaaaa.mecc.core.config.AlertsConfig;
import io.github.codaaaaaa.mecc.core.crafting.CraftingOrder;
import io.github.codaaaaaa.mecc.core.crafting.OrderTarget;
import io.github.codaaaaaa.mecc.core.live.LiveEvent;
import io.github.codaaaaaa.mecc.core.networks.GridStatus;
import io.github.codaaaaaa.mecc.core.networks.NetworkReconciler.Resolution;
import io.github.codaaaaaa.mecc.core.networks.NetworkRecordStatus;
import io.github.codaaaaaa.mecc.core.persistence.DataStore;
import io.github.codaaaaaa.mecc.core.resources.ResourceIndex;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform.CpuCapture;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform.CpuState;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform.JobState;
import io.github.codaaaaaa.mecc.runtime.crafting.CpuSnapshots;
import io.github.codaaaaaa.mecc.runtime.crafting.CraftingTracker;
import io.github.codaaaaaa.mecc.runtime.crafting.ResourceLabels;
import io.github.codaaaaaa.mecc.runtime.machines.DefaultMachineService;
import io.github.codaaaaaa.mecc.runtime.networks.NetworkDirectory;
import io.github.codaaaaaa.mecc.runtime.networks.NetworkGuard;
import io.github.codaaaaaa.mecc.runtime.resources.ResourceSnapshots;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The server-side alert engine (spec section 23). Condition rules are checked every interval from data ME Control
 * Center already keeps: network status from discovery, amounts from the terminal's storage snapshots (one per network
 * with resource rules, however many rules), and crafting progress from the order tracker. Craft completed/failed rules
 * fire when the tracker reports an order's end.
 *
 * <p>A condition that cannot be judged right now (the network is ambiguous, energy capacity unknown, storage not
 * readable, not enough history yet) leaves the rule's state as it was: no alert and no false recovery.
 *
 * <p>ponytail: percentage-change history and crafting progress are kept in memory, one reading per check. After a
 * restart a percentage rule needs one window of readings before it can fire, and a stall is measured from the first
 * reading. Read the stored time series instead if that ever matters.
 */
public final class AlertMonitor {
    private static final Logger LOGGER = LoggerFactory.getLogger(AlertMonitor.class);
    static final Duration EVENT_RETENTION = Duration.ofDays(30);

    private final DataStore store;
    private final NetworkDirectory directory;
    private final NetworkGuard guard;
    private final ResourceSnapshots snapshots;
    private final CpuSnapshots cpus;
    private final CraftingTracker tracker;
    private final DefaultMachineService machines;
    private final ResourceLabels labels;
    private final AlertNotifier notifier;
    private final AlertsConfig config;
    private final Clock clock;
    private final AtomicBoolean checking = new AtomicBoolean();
    /** Per percentage-change rule: {@code [epochMillis, amount]} readings, oldest first. Networks are checked in parallel. */
    private final Map<UUID, Deque<long[]>> history = new ConcurrentHashMap<>();
    /** Per running job ({@link Subject#key()}): its last progress and since when it has been that. */
    private final Map<String, Progress> progress = new HashMap<>();
    /** {@code ruleId/subjectKey} of stalled jobs that were announced and not yet resolved. */
    private final Set<String> stalledJobs = new HashSet<>();
    /** {@code ruleId/subjectKey} of stuck machines that were announced and not yet resolved. */
    private final Set<String> stuckMachines = new HashSet<>();

    private record Progress(double value, Instant since) {
    }

    public AlertMonitor(DataStore store, NetworkDirectory directory, NetworkGuard guard, ResourceSnapshots snapshots,
                        CpuSnapshots cpus, CraftingTracker tracker, DefaultMachineService machines, ResourceLabels labels,
                        AlertNotifier notifier, AlertsConfig config, Clock clock) {
        this.store = store;
        this.directory = directory;
        this.guard = guard;
        this.snapshots = snapshots;
        this.cpus = cpus;
        this.tracker = tracker;
        this.machines = machines;
        this.labels = labels;
        this.notifier = notifier;
        this.config = config;
        this.clock = clock;
    }

    public void start(ScheduledExecutorService scheduler) {
        long interval = config.checkIntervalSeconds();
        scheduler.scheduleWithFixedDelay(() -> checkOnce().exceptionally(error -> {
            LOGGER.debug("ME Control Center could not check alert rules: {}", error.toString());
            return 0;
        }), interval, interval, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(() -> store.write(repos ->
                        repos.alerts().purgeEvents(clock.instant().minus(EVENT_RETENTION)))
                .exceptionally(error -> {
                    LOGGER.warn("ME Control Center could not delete old alert events", error);
                    return 0;
                }), 5, 60 * 24, TimeUnit.MINUTES);
    }

    /** What is known about one network right now; {@code null} fields cannot be judged. */
    record Facts(Boolean offline, Double energyPercent, Boolean cpusSaturated, ResourceIndex storage) {
        static final Facts UNKNOWN = new Facts(null, null, null, null);
    }

    /** Evaluates every enabled condition and stall rule once. Returns the number of events raised. */
    public CompletableFuture<Integer> checkOnce() {
        if (!checking.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(0);
        }
        return store.read(repos -> repos.alerts().enabledRules())
                .thenCompose(rules -> {
                    Instant now = clock.instant();
                    Map<UUID, List<AlertRule>> byNetwork = new LinkedHashMap<>();
                    rules.stream().filter(rule -> rule.type().condition())
                            .forEach(rule -> byNetwork.computeIfAbsent(rule.networkId(), id -> new ArrayList<>()).add(rule));
                    history.keySet().retainAll(rules.stream().filter(rule -> rule.type().needsWindow())
                            .map(AlertRule::id).toList());
                    List<CompletableFuture<List<Transition>>> checks = new ArrayList<>();
                    byNetwork.forEach((networkId, networkRules) -> checks.add(facts(networkId, networkRules)
                            .thenApply(facts -> transitions(networkRules, facts, baselines(networkRules, facts, now), now))));
                    List<AlertRule> stallRules = ofType(rules, AlertType.CRAFT_STALLED);
                    checks.add(jobs(networksOf(stallRules), now)
                            .thenApply(jobs -> episodes(stallRules, jobs, stalledJobs, now)));
                    List<AlertRule> machineRules = ofType(rules, AlertType.MACHINE_STUCK);
                    checks.add(machines(networksOf(machineRules))
                            .thenApply(stuck -> episodes(machineRules, stuck, stuckMachines, now)));
                    return CompletableFuture.allOf(checks.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
                        List<Transition> all = new ArrayList<>();
                        checks.forEach(check -> all.addAll(check.join()));
                        return all;
                    });
                })
                .thenCompose(this::apply)
                .whenComplete((ignored, error) -> checking.set(false));
    }

    private CompletableFuture<Facts> facts(UUID networkId, List<AlertRule> rules) {
        NetworkDirectory.State state = directory.current();
        if (state == null || !state.records().containsKey(networkId)) {
            return CompletableFuture.completedFuture(Facts.UNKNOWN);
        }
        Resolution resolution = state.result().resolutions().get(networkId);
        if (resolution == null || resolution.status() == NetworkRecordStatus.OFFLINE) {
            return CompletableFuture.completedFuture(new Facts(true, null, null, null));
        }
        if (resolution.status() != NetworkRecordStatus.ONLINE) {
            return CompletableFuture.completedFuture(Facts.UNKNOWN);
        }
        GridStatus status = resolution.grid().status();
        Double energy = status.storedEnergy() != null && status.energyCapacity() != null && status.energyCapacity() > 0
                ? status.storedEnergy() / status.energyCapacity() * 100 : null;
        Boolean saturated = status.craftingCpus() != null && status.craftingCpus() > 0 && status.busyCraftingCpus() != null
                ? status.busyCraftingCpus() >= status.craftingCpus() : null;
        boolean offline = !status.powered();
        if (offline || rules.stream().noneMatch(rule -> rule.type().needsResource())) {
            return CompletableFuture.completedFuture(new Facts(offline, energy, saturated, null));
        }
        return snapshots.latest(networkId, resolution.grid().runtimeKey())
                .thenApply(snapshot -> new Facts(false, energy, saturated, snapshot.index()))
                .exceptionally(error -> new Facts(false, energy, saturated, null));
    }

    private static long amount(ResourceIndex storage, AlertRule rule) {
        int position = storage.indexOf(rule.resource().resourceId());
        return position < 0 ? 0 : storage.amount(position);
    }

    /**
     * Records the current amount for each percentage-change rule and returns, per rule, the amount one window ago.
     * A rule is missing from the result until its history covers the window.
     */
    Map<UUID, Long> baselines(List<AlertRule> rules, Facts facts, Instant now) {
        Map<UUID, Long> baselines = new HashMap<>();
        for (AlertRule rule : rules) {
            if (!rule.type().needsWindow() || rule.window() == null || rule.resource() == null || facts.storage() == null) {
                continue;
            }
            Deque<long[]> readings = history.computeIfAbsent(rule.id(), id -> new ArrayDeque<>());
            readings.addLast(new long[] {now.toEpochMilli(), amount(facts.storage(), rule)});
            long cutoff = now.toEpochMilli() - rule.window() * 60_000L;
            // Keep exactly one reading at or before the cutoff: the amount one window ago.
            while (readings.size() >= 2) {
                long[] oldest = readings.pollFirst();
                if (readings.getFirst()[0] > cutoff) {
                    readings.addFirst(oldest);
                    break;
                }
            }
            if (readings.getFirst()[0] <= cutoff) {
                baselines.put(rule.id(), readings.getFirst()[1]);
            }
        }
        return baselines;
    }

    /** Whether a condition holds, or {@code null} when it cannot be judged. Also the value to report. */
    record Reading(Boolean holds, Long value) {
        static final Reading UNKNOWN = new Reading(null, null);
    }

    static Reading read(AlertRule rule, Facts facts, Long baseline) {
        return switch (rule.type()) {
            case RESOURCE_BELOW, RESOURCE_ABOVE -> {
                if (facts.storage() == null || rule.resource() == null || rule.threshold() == null) {
                    yield Reading.UNKNOWN;
                }
                long amount = amount(facts.storage(), rule);
                yield new Reading(rule.type() == AlertType.RESOURCE_BELOW ? amount < rule.threshold() : amount > rule.threshold(),
                        amount);
            }
            case RESOURCE_DROP, RESOURCE_RISE -> {
                // A range that starts at 0 has no percentage (spec section 21.4): never divide by zero.
                if (facts.storage() == null || rule.resource() == null || rule.threshold() == null || baseline == null
                        || baseline == 0) {
                    yield Reading.UNKNOWN;
                }
                double change = (amount(facts.storage(), rule) - baseline) * 100.0 / baseline;
                double toward = rule.type() == AlertType.RESOURCE_DROP ? -change : change;
                yield new Reading(toward >= rule.threshold(), Math.round(toward));
            }
            case NETWORK_OFFLINE -> new Reading(facts.offline(), null);
            case ENERGY_LOW -> facts.energyPercent() == null || rule.threshold() == null
                    ? Reading.UNKNOWN
                    : new Reading(facts.energyPercent() < rule.threshold(), (long) Math.floor(facts.energyPercent()));
            case CPU_SATURATED -> new Reading(facts.cpusSaturated(), null);
            case CRAFT_COMPLETED, CRAFT_FAILED, CRAFT_STALLED, MACHINE_STUCK -> Reading.UNKNOWN;
        };
    }

    /** A rule's new state and, when someone must hear about it, the event. */
    record Transition(AlertRule rule, AlertState state, Instant notifiedAt, AlertEvent event) {
    }

    static List<Transition> transitions(List<AlertRule> rules, Facts facts, Map<UUID, Long> baselines, Instant now) {
        List<Transition> transitions = new ArrayList<>();
        for (AlertRule rule : rules) {
            Reading reading = read(rule, facts, baselines.get(rule.id()));
            if (reading.holds() == null) {
                continue;
            }
            AlertState next = rule.next(reading.holds(), now);
            if (next == rule.state()) {
                continue;
            }
            AlertEvent.Kind kind = next == AlertState.FIRING ? AlertEvent.Kind.TRIGGERED
                    : rule.state() == AlertState.FIRING ? AlertEvent.Kind.RESOLVED : null;
            AlertEvent event = kind == null ? null : new AlertEvent(0, rule.id(), rule.playerUuid(), rule.networkId(),
                    rule.type(), kind, now, rule.resource(), reading.value(), rule.threshold(), null);
            transitions.add(new Transition(rule, next, next == AlertState.FIRING ? now : rule.notifiedAt(), event));
        }
        return transitions;
    }

    private static List<AlertRule> ofType(List<AlertRule> rules, AlertType type) {
        return rules.stream().filter(rule -> rule.type() == type).toList();
    }

    private static Set<UUID> networksOf(List<AlertRule> rules) {
        Set<UUID> networks = new HashSet<>();
        rules.forEach(rule -> networks.add(rule.networkId()));
        return networks;
    }

    /**
     * Something that can get stuck: a running crafting job or a machine.
     *
     * @param key            unique across networks
     * @param owner          the player it belongs to (a job's requester), or {@code null} for anyone on the network
     * @param target         what it makes, or the machine block; {@code null} when unknown
     * @param unchangedSince since when it has not moved while something is expected of it; {@code null} when it is fine
     */
    record Subject(String key, UUID networkId, UUID owner, OrderTarget target, Long amount, UUID orderId,
                   Instant unchangedSince) {
    }

    /**
     * Every running job with a known progress on the given networks, including jobs started in game: their owner is
     * the order's creator, or the player the crafting system recorded as requester.
     */
    private CompletableFuture<List<Subject>> jobs(Set<UUID> networkIds, Instant now) {
        Map<UUID, CompletableFuture<CpuCapture>> reads = new HashMap<>();
        for (UUID networkId : networkIds) {
            guard.onlineGridKey(networkId).ifPresent(gridKey ->
                    reads.put(networkId, cpus.latest(networkId, gridKey).exceptionally(error -> null)));
        }
        return CompletableFuture.allOf(reads.values().toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            List<Subject> subjects = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            reads.forEach((networkId, read) -> {
                CpuCapture capture = read.join();
                if (capture == null) {
                    return;
                }
                for (CpuState cpu : capture.cpus()) {
                    JobState job = cpu.job();
                    if (job == null || job.progress() == null) {
                        continue;
                    }
                    String key = networkId + "/" + (job.jobId() != null ? job.jobId() : cpu.id() + ":" + job.output().id());
                    seen.add(key);
                    Progress last = progress.get(key);
                    if (last == null || last.value() != job.progress()) {
                        progress.put(key, new Progress(job.progress(), now));
                    }
                    var order = tracker.byJob(networkId, cpu.id(), job);
                    subjects.add(new Subject(key, networkId,
                            order.map(tracked -> tracked.order().creatorUuid()).orElse(job.requesterUuid()),
                            labels.target(job.output()), job.amount(), order.map(tracked -> tracked.order().id()).orElse(null),
                            progress.get(key).since()));
                }
            });
            // Jobs that ended are forgotten; jobs on networks that could not be read keep their history.
            progress.keySet().removeIf(key -> reads.containsKey(UUID.fromString(key.substring(0, key.indexOf('/'))))
                    && !seen.contains(key));
            return subjects;
        });
    }

    /** Every machine on the given networks, stuck since when if something is expected of it. */
    private CompletableFuture<List<Subject>> machines(Set<UUID> networkIds) {
        List<CompletableFuture<List<Subject>>> reads = new ArrayList<>();
        for (UUID networkId : networkIds) {
            guard.onlineGridKey(networkId).ifPresent(gridKey -> reads.add(machines.latest(networkId, gridKey)
                    .thenApply(snapshot -> snapshot.machines().stream().map(observation -> new Subject(
                            networkId + "/" + observation.state().id(), networkId, null,
                            observation.state().block() == null ? null : labels.target(observation.state().block()),
                            null, null, observation.stuckSince())).toList())
                    .exceptionally(error -> List.of())));
        }
        return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).thenApply(ignored ->
                reads.stream().flatMap(read -> read.join().stream()).toList());
    }

    /**
     * Stall and machine rules (spec section 23): each subject that has not moved for the rule's threshold in minutes is
     * announced once, and resolved when it moves again. Stall rules only cover their owner's jobs; both may be narrowed
     * to one resource. A rule is firing while any of its subjects is stuck.
     *
     * @param announced {@code ruleId/subjectKey} of the rule type's announced, unresolved episodes
     */
    static List<Transition> episodes(List<AlertRule> rules, List<Subject> subjects, Set<String> announced, Instant now) {
        Set<String> current = new HashSet<>();
        subjects.forEach(subject -> current.add(subject.key()));
        announced.removeIf(key -> !current.contains(key.substring(key.indexOf('/') + 1)));

        List<Transition> transitions = new ArrayList<>();
        for (AlertRule rule : rules) {
            boolean wasFiring = rule.state() != AlertState.OK;
            Instant notifiedAt = rule.notifiedAt();
            boolean reported = false;
            for (Subject subject : subjects) {
                if (!subject.networkId().equals(rule.networkId())
                        || (rule.type() == AlertType.CRAFT_STALLED && !Objects.equals(subject.owner(), rule.playerUuid()))
                        || (rule.resource() != null && (subject.target() == null
                                || !rule.resource().resourceId().equals(subject.target().resourceId())))) {
                    continue;
                }
                boolean stuck = subject.unchangedSince() != null && rule.threshold() != null
                        && !subject.unchangedSince().plusSeconds(rule.threshold() * 60).isAfter(now);
                String key = rule.id() + "/" + subject.key();
                AlertEvent.Kind kind = null;
                if (stuck && announced.add(key)) {
                    kind = AlertEvent.Kind.TRIGGERED;
                    notifiedAt = now;
                } else if (!stuck && announced.remove(key)) {
                    kind = AlertEvent.Kind.RESOLVED;
                }
                if (kind != null) {
                    reported = true;
                    transitions.add(new Transition(rule, AlertState.OK, notifiedAt, new AlertEvent(0, rule.id(),
                            rule.playerUuid(), rule.networkId(), rule.type(), kind, now, subject.target(), subject.amount(),
                            rule.threshold(), subject.orderId())));
                }
            }
            boolean firing = announced.stream().anyMatch(key -> key.startsWith(rule.id() + "/"));
            if (firing != wasFiring || reported) {
                // The state last: a rule shows as active while any of its subjects is stuck.
                transitions.add(new Transition(rule, firing ? AlertState.FIRING : AlertState.OK, notifiedAt, null));
            }
        }
        return transitions;
    }

    private CompletableFuture<Integer> apply(List<Transition> transitions) {
        if (transitions.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        return store.write(repos -> {
            List<AlertEvent> stored = new ArrayList<>();
            for (Transition transition : transitions) {
                // A rule deleted or disabled since it was read raises nothing.
                AlertRule current = repos.alerts().findRule(transition.rule().id()).orElse(null);
                if (current == null || !current.enabled()) {
                    continue;
                }
                repos.alerts().setState(current.id(), transition.state(), transition.notifiedAt());
                if (transition.event() != null) {
                    stored.add(transition.event().withId(repos.alerts().appendEvent(transition.event())));
                }
            }
            return stored;
        }).thenApply(events -> {
            notifier.deliver(events);
            return events.size();
        });
    }

    /** Craft rules (spec section 23): the owner's own orders, optionally only for one resource. */
    public void orderChanged(CraftingOrder order, String eventType) {
        if (!config.enabled()) {
            return;
        }
        AlertType type = LiveEvent.ORDER_COMPLETED.equals(eventType) ? AlertType.CRAFT_COMPLETED
                : LiveEvent.ORDER_FAILED.equals(eventType) ? AlertType.CRAFT_FAILED : null;
        if (type == null) {
            return;
        }
        Instant now = clock.instant();
        store.write(repos -> {
            List<AlertEvent> stored = new ArrayList<>();
            for (AlertRule rule : repos.alerts().rules(order.creatorUuid(), order.networkId())) {
                if (rule.enabled() && rule.type() == type
                        && (rule.resource() == null || rule.resource().resourceId().equals(order.target().resourceId()))) {
                    AlertEvent event = new AlertEvent(0, rule.id(), rule.playerUuid(), rule.networkId(), type,
                            AlertEvent.Kind.TRIGGERED, now, order.target(), order.amount(), null, order.id());
                    stored.add(event.withId(repos.alerts().appendEvent(event)));
                    repos.alerts().setState(rule.id(), AlertState.OK, now);
                }
            }
            return stored;
        }).thenAccept(notifier::deliver).exceptionally(error -> {
            LOGGER.warn("ME Control Center could not record a crafting alert", error);
            return null;
        });
    }
}
