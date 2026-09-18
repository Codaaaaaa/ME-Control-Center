package io.github.codaaaaaa.mecc.runtime.crafting;

import io.github.codaaaaaa.mecc.core.auth.AuthViews.UserView;
import io.github.codaaaaaa.mecc.core.crafting.CraftingOrder;
import io.github.codaaaaaa.mecc.core.crafting.CraftingViews.Confidence;
import io.github.codaaaaaa.mecc.core.crafting.CraftingViews.CpuCandidateView;
import io.github.codaaaaaa.mecc.core.crafting.CraftingViews.CpuJobView;
import io.github.codaaaaaa.mecc.core.crafting.CraftingViews.CpuList;
import io.github.codaaaaaa.mecc.core.crafting.CraftingViews.CpuRef;
import io.github.codaaaaaa.mecc.core.crafting.CraftingViews.CpuUnsuitableReason;
import io.github.codaaaaaa.mecc.core.crafting.CraftingViews.CpuView;
import io.github.codaaaaaa.mecc.core.crafting.CraftingViews.FailureView;
import io.github.codaaaaaa.mecc.core.crafting.CraftingViews.OrderView;
import io.github.codaaaaaa.mecc.core.crafting.CraftingViews.PlanEntryView;
import io.github.codaaaaaa.mecc.core.crafting.CraftingViews.PlanState;
import io.github.codaaaaaa.mecc.core.crafting.CraftingViews.PlanView;
import io.github.codaaaaaa.mecc.core.crafting.CraftingViews.ProgressView;
import io.github.codaaaaaa.mecc.core.crafting.OrderState;
import io.github.codaaaaaa.mecc.core.permissions.NetworkAccess;
import io.github.codaaaaaa.mecc.core.permissions.NetworkCapability;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform.CpuCapture;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform.CpuState;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform.JobState;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform.PlanEntry;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform.PlanSummary;
import io.github.codaaaaaa.mecc.runtime.crafting.CraftingTracker.Tracked;
import io.github.codaaaaaa.mecc.runtime.crafting.PlanStore.Plan;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Builds crafting API views. Views depend on the viewer (what they may cancel) and the locale, so the same
 * order or CPU capture is presented once per viewer. Pure apart from reading the tracker's live state.
 */
public final class CraftingPresenter {
    /** Plans list at most this many lines; huge modpack trees are summarized, not shipped whole. */
    static final int MAX_PLAN_ENTRIES = 300;
    static final String MACHINE_ONLY = "MACHINE_ONLY";

    private final ResourceLabels labels;
    private final CraftingTracker tracker;
    private final Clock clock;
    private final Supplier<String> assetVersion;

    public CraftingPresenter(ResourceLabels labels, CraftingTracker tracker, Clock clock, Supplier<String> assetVersion) {
        this.labels = labels;
        this.tracker = tracker;
        this.clock = clock;
        this.assetVersion = assetVersion;
    }

    /** Changes when installed assets change; part of icon URLs. */
    public String assetVersion() {
        return assetVersion.get();
    }

    // --- plans --------------------------------------------------------------------------------------

    public PlanView plan(Plan plan, String locale) {
        PlanSummary summary = plan.summary;
        if (plan.state != PlanState.READY || summary == null) {
            return new PlanView(plan.id, plan.state, plan.networkId, null, plan.requestedAmount, null, null, null, false,
                    List.of(), 0, List.of(),
                    plan.failure == null ? null : plan.failure.code().name(),
                    plan.failure == null ? null : plan.failure.getMessage(),
                    plan.createdAt, plan.expiresAt, assetVersion.get());
        }
        List<PlanEntry> sorted = summary.entries().stream()
                .sorted(Comparator.comparingLong((PlanEntry entry) -> entry.missing()).reversed()
                        .thenComparing(Comparator.comparingLong((PlanEntry entry) -> entry.toCraft()).reversed())
                        .thenComparing(Comparator.comparingLong((PlanEntry entry) -> entry.stored()).reversed()))
                .toList();
        List<PlanEntryView> entries = sorted.stream().limit(MAX_PLAN_ENTRIES)
                .map(entry -> new PlanEntryView(labels.label(entry.resource(), locale), entry.stored(), entry.toCraft(),
                        entry.missing()))
                .toList();
        List<CpuCandidateView> cpus = plan.cpus.stream()
                .map(cpu -> new CpuCandidateView(cpu.id(), labels.text(cpu.name(), locale), cpu.busy(), cpu.online(),
                        cpu.storageBytes(), cpu.coProcessors(), cpu.selectionMode(), unsuitable(cpu, summary.bytes())))
                .sorted(CPU_ORDER_CANDIDATES)
                .toList();
        return new PlanView(plan.id, PlanState.READY, plan.networkId, labels.label(summary.output(), locale),
                plan.requestedAmount, summary.amount(), summary.complete(), summary.bytes(), summary.multiplePaths(),
                entries, sorted.size(), cpus, null, null, plan.createdAt, plan.expiresAt, assetVersion.get());
    }

    static CpuUnsuitableReason unsuitable(CpuState cpu, long bytes) {
        if (Boolean.FALSE.equals(cpu.online())) return CpuUnsuitableReason.OFFLINE;
        if (cpu.busy()) return CpuUnsuitableReason.BUSY;
        if (cpu.storageBytes() < bytes) return CpuUnsuitableReason.TOO_SMALL;
        if (MACHINE_ONLY.equals(cpu.selectionMode())) return CpuUnsuitableReason.EXCLUDED;
        return null;
    }

    private static final Comparator<CpuCandidateView> CPU_ORDER_CANDIDATES = Comparator
            .comparing((CpuCandidateView cpu) -> cpu.reason() != null)
            .thenComparing(CpuCandidateView::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
            .thenComparing(CpuCandidateView::id);

    // --- orders -------------------------------------------------------------------------------------

    public OrderView order(CraftingOrder stored, NetworkAccess access, UUID viewer, Map<UUID, UserView> users, String locale) {
        Optional<Tracked> live = stored.state().active() ? tracker.find(stored.id()) : Optional.empty();
        CraftingOrder order = live.map(Tracked::order).orElse(stored);
        JobState job = live.map(Tracked::job).orElse(null);
        boolean observed = live.map(Tracked::observed).orElse(false);

        ProgressView progress;
        Long elapsed = null;
        switch (order.state()) {
            case RUNNING -> {
                Double percent = job != null && job.progress() != null ? job.progress() * 100 : order.progressPercent();
                progress = percent == null ? ProgressView.unknown(order.amount())
                        : new ProgressView(null, null, order.amount(), clampPercent(percent), Confidence.AUTHORITATIVE);
                if (job != null && job.elapsed() != null) {
                    elapsed = job.elapsed().toMillis();
                } else if (order.startedAt() != null) {
                    elapsed = Duration.between(order.startedAt(), clock.instant()).toMillis();
                }
            }
            case COMPLETED -> progress = ProgressView.finished(order.amount());
            case SUBMITTING -> progress = ProgressView.unknown(order.amount());
            default -> progress = order.progressPercent() == null ? ProgressView.unknown(order.amount())
                    : new ProgressView(null, null, order.amount(), clampPercent(order.progressPercent()), Confidence.AUTHORITATIVE);
        }
        if (!order.state().active() && order.startedAt() != null && order.endedAt() != null) {
            elapsed = Duration.between(order.startedAt(), order.endedAt()).toMillis();
        }
        CpuRef cpu = order.cpuId() == null ? null : new CpuRef(order.cpuId(), order.cpuName());
        FailureView failure = order.failureCode() == null ? null : new FailureView(order.failureCode(), order.failureMessage());
        return new OrderView(order.id(), order.networkId(), user(order.creatorUuid(), users), labels.label(order.target(), locale),
                order.amount(), order.state(), order.source(), order.createdAt(), order.startedAt(), order.endedAt(), cpu,
                progress, elapsed, failure, order.state() == OrderState.RUNNING && mayCancel(access, viewer, order.creatorUuid()),
                observed, order.lastObservedAt());
    }

    /** Players whose names an order view needs. */
    public static Set<UUID> users(Collection<CraftingOrder> orders) {
        Set<UUID> ids = new HashSet<>();
        orders.forEach(order -> ids.add(order.creatorUuid()));
        return ids;
    }

    // --- CPUs ---------------------------------------------------------------------------------------

    /** Players whose names a CPU list needs: creators of the ME Control Center orders running on it. */
    public Set<UUID> users(UUID networkId, CpuCapture capture) {
        Set<UUID> ids = new HashSet<>();
        for (CpuState cpu : capture.cpus()) {
            tracker.byJob(networkId, cpu.id(), cpu.job()).ifPresent(tracked -> ids.add(tracked.order().creatorUuid()));
        }
        return ids;
    }

    public CpuList cpus(UUID networkId, CpuCapture capture, NetworkAccess access, UUID viewer, Map<UUID, UserView> users,
                        String locale) {
        List<CpuView> cpus = capture.cpus().stream()
                .map(cpu -> cpu(networkId, cpu, access, viewer, users, locale))
                .sorted(Comparator.comparing(CpuView::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(CpuView::id))
                .toList();
        return new CpuList(capture.capturedAt(), cpus, assetVersion.get());
    }

    private CpuView cpu(UUID networkId, CpuState cpu, NetworkAccess access, UUID viewer, Map<UUID, UserView> users,
                        String locale) {
        CpuJobView job = null;
        JobState state = cpu.job();
        if (state != null) {
            Optional<Tracked> order = tracker.byJob(networkId, cpu.id(), state);
            UUID creator = order.map(tracked -> tracked.order().creatorUuid()).orElse(null);
            ProgressView progress = state.progress() == null ? ProgressView.unknown(state.amount())
                    : new ProgressView(null, null, state.amount(), clampPercent(state.progress() * 100), Confidence.AUTHORITATIVE);
            job = new CpuJobView(state.jobId(), labels.label(state.output(), locale), state.amount(), progress,
                    state.elapsed() == null ? null : state.elapsed().toMillis(),
                    order.map(tracked -> tracked.order().id()).orElse(null),
                    creator == null ? null : user(creator, users),
                    mayCancel(access, viewer, creator));
        }
        return new CpuView(cpu.id(), labels.text(cpu.name(), locale), cpu.location(), cpu.busy(), cpu.online(),
                cpu.storageBytes(), cpu.coProcessors(), cpu.selectionMode(), job);
    }

    /**
     * @param creator the order's creator, or {@code null} for jobs ME Control Center did not start
     */
    static boolean mayCancel(NetworkAccess access, UUID viewer, UUID creator) {
        if (creator != null && creator.equals(viewer)) {
            return access.allows(NetworkCapability.CANCEL_OWN_CRAFT);
        }
        return access.allows(NetworkCapability.CANCEL_ANY_CRAFT);
    }

    /** Which capability cancelling requires, for permission checks and audit. */
    static NetworkCapability cancelCapability(UUID viewer, UUID creator) {
        return creator != null && creator.equals(viewer) ? NetworkCapability.CANCEL_OWN_CRAFT : NetworkCapability.CANCEL_ANY_CRAFT;
    }

    private static double clampPercent(double percent) {
        return Math.max(0, Math.min(100, percent));
    }

    private static UserView user(UUID id, Map<UUID, UserView> users) {
        UserView view = users.get(id);
        return view != null ? view : new UserView(id, null);
    }

    Instant now() {
        return clock.instant();
    }
}
