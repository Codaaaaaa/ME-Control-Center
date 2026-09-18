package io.github.codaaaaaa.mecc.forge.ae2;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.CraftingJobStatus;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.crafting.UnsuitableCpus;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.mojang.authlib.GameProfile;
import io.github.codaaaaaa.mecc.core.error.ErrorCode;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import io.github.codaaaaaa.mecc.core.networks.BlockLocation;
import io.github.codaaaaaa.mecc.core.resources.ResourceId;
import io.github.codaaaaaa.mecc.core.resources.ResourceText;
import io.github.codaaaaaa.mecc.core.users.PlayerProfile;
import io.github.codaaaaaa.mecc.forge.ComponentText;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform;
import io.github.codaaaaaa.mecc.platform.ServerThreadOnly;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.util.FakePlayerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AE2 15 autocrafting (spec sections 9-12).
 *
 * <p>Jobs are submitted exactly like the in-game crafting terminal does: standalone, on behalf of the
 * requesting player, so AE2 itself revalidates ingredients and CPUs, and the player gets AE2's own
 * "crafting finished" notice when online.
 *
 * <p>Following jobs: AE2's API reports a CPU's current job but not its identity or how it ended. For AE2's own
 * CPU clusters, ME Control Center reads the job's crafting link (public, but outside the API jar): its ID survives server
 * restarts and CPU rebuilds, and a standalone link is flagged when, and only when, the job is cancelled. So a
 * job that left its (still existing) CPU without being cancelled has completed. CPUs of other types are
 * followed by CPU and output only, and their outcome is reported as unknown rather than guessed.
 *
 * <p>Pinned to AE2 {@value Ae2Integration#TESTED_VERSION}; every AE2 internal used here is in this class.
 */
public final class Ae2CraftingPlatform implements CraftingPlatform {
    private static final Logger LOGGER = LoggerFactory.getLogger(Ae2CraftingPlatform.class);
    /** Links of jobs ME Control Center follows, kept so their outcome can still be read after the job left its CPU. */
    private static final int MAX_RETAINED_JOBS = 4096;

    private final MinecraftServer server;
    private final Ae2StoragePlatform storage;
    /** Server thread only. */
    private final Map<ICraftingCPU, String> foreignCpuIds = new WeakHashMap<>();
    private final AtomicLong foreignCpuSerial = new AtomicLong();
    /** Server thread only. */
    private final Map<String, RetainedJob> retained = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, RetainedJob> eldest) {
            return size() > MAX_RETAINED_JOBS;
        }
    };

    private record RetainedJob(ICraftingLink link, CraftingCPUCluster cluster) {
    }

    public Ae2CraftingPlatform(MinecraftServer server, Ae2StoragePlatform storage) {
        this.server = server;
        this.storage = storage;
    }

    // --- CPUs ---------------------------------------------------------------------------------------

    @Override
    @ServerThreadOnly
    public CpuCapture captureCpus(String gridKey, Set<String> watchedJobIds) {
        requireServerThread();
        IGrid grid = grid(gridKey);
        List<CpuState> cpus = new ArrayList<>();
        Map<String, JobFate> fates = new HashMap<>();
        for (ICraftingCPU cpu : grid.getCraftingService().getCpus()) {
            CpuState state = describe(cpu);
            cpus.add(state);
            JobState job = state.job();
            if (job != null && job.jobId() != null && watchedJobIds.contains(job.jobId())) {
                fates.put(job.jobId(), JobFate.RUNNING);
                // Also refreshes the link after a restart or a CPU rebuild, which recreate it from NBT.
                ICraftingLink link = link(cpu);
                if (link != null && cpu instanceof CraftingCPUCluster cluster) {
                    retained.put(job.jobId(), new RetainedJob(link, cluster));
                }
            }
        }
        for (String jobId : watchedJobIds) {
            if (!fates.containsKey(jobId)) {
                JobFate fate = fate(jobId);
                fates.put(jobId, fate);
                if (fate == JobFate.COMPLETED || fate == JobFate.CANCELLED) {
                    retained.remove(jobId);
                }
            }
        }
        return new CpuCapture(Instant.now(), cpus, fates);
    }

    private JobFate fate(String jobId) {
        RetainedJob job = retained.get(jobId);
        if (job == null) {
            return JobFate.NOT_FOUND;
        }
        if (job.cluster().craftingLogic.getLastLink() == job.link()) {
            // Still the cluster's job, but the cluster is unloaded or now part of another grid.
            return JobFate.UNOBSERVABLE;
        }
        if (job.link().isCanceled()) {
            return JobFate.CANCELLED;
        }
        // AE2 marks a standalone link only on cancellation, so a job gone from its CPU otherwise completed.
        return JobFate.COMPLETED;
    }

    private CpuState describe(ICraftingCPU cpu) {
        JobState job = null;
        try {
            CraftingJobStatus status = cpu.getJobStatus();
            if (status != null && status.crafting() != null) {
                ICraftingLink link = link(cpu);
                Double progress = status.totalItems() > 0
                        ? Math.max(0.0, Math.min(1.0, status.progress() / (double) status.totalItems()))
                        : null;
                job = new JobState(link == null ? null : link.getCraftingID().toString(),
                        storage.describe(status.crafting().what()), status.crafting().amount(), progress,
                        Duration.ofNanos(Math.max(0, status.elapsedTimeNanos())));
            }
        } catch (RuntimeException e) {
            LOGGER.debug("Could not read the job of crafting CPU {}", cpu, e);
        }
        ResourceText name = cpu.getName() == null ? null : ComponentText.of(cpu.getName());
        BlockLocation location = null;
        Boolean online = null;
        if (cpu instanceof CraftingCPUCluster cluster) {
            location = location(cluster);
            online = cluster.isActive();
        }
        return new CpuState(cpuId(cpu), name, location, cpu.isBusy(), online, cpu.getAvailableStorage(),
                cpu.getCoProcessors(), cpu.getSelectionMode().name(), job);
    }

    private static ICraftingLink link(ICraftingCPU cpu) {
        return cpu instanceof CraftingCPUCluster cluster ? cluster.craftingLogic.getLastLink() : null;
    }

    private static BlockLocation location(CraftingCPUCluster cluster) {
        try {
            Level level = cluster.getLevel();
            BlockPos min = cluster.getBoundsMin();
            return level == null ? null
                    : new BlockLocation(level.dimension().location().toString(), min.getX(), min.getY(), min.getZ());
        } catch (RuntimeException e) {
            return null; // The cluster has no core block right now.
        }
    }

    /** Position-based for AE2's clusters (stable across restarts and rebuilds), per object otherwise. */
    private String cpuId(ICraftingCPU cpu) {
        if (cpu instanceof CraftingCPUCluster cluster) {
            BlockLocation location = location(cluster);
            if (location != null) {
                return "c" + shortHash(location.key());
            }
        }
        return foreignCpuIds.computeIfAbsent(cpu, key -> "x" + foreignCpuSerial.incrementAndGet());
    }

    private static String shortHash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private ICraftingCPU findCpu(IGrid grid, String cpuId) {
        for (ICraftingCPU cpu : grid.getCraftingService().getCpus()) {
            if (cpuId(cpu).equals(cpuId)) {
                return cpu;
            }
        }
        return null;
    }

    // --- calculation --------------------------------------------------------------------------------

    @Override
    @ServerThreadOnly
    public Calculation beginCalculation(String gridKey, ResourceId resource, long amount, PlayerProfile requester) {
        requireServerThread();
        IGrid grid = grid(gridKey);
        ICraftingService crafting = grid.getCraftingService();
        AEKey key = craftableKey(crafting, resource);
        IGridNode pivot = grid.getPivot();
        Level level = pivot == null ? null : pivot.getLevel();
        if (key == null || level == null) {
            throw new MeccException(ErrorCode.NOT_CRAFTABLE, "This ME network has no pattern that produces this resource");
        }
        IActionSource source = actionSource(requester, level, grid);
        Future<ICraftingPlan> future = crafting.beginCraftingCalculation(level, () -> source, key, amount,
                CalculationStrategy.REPORT_MISSING_ITEMS);
        return new Ae2Calculation(future);
    }

    /** The craftable key with this identity; the variant hash tells apart keys with the same registry id. */
    private static AEKey craftableKey(ICraftingService crafting, ResourceId resource) {
        for (AEKey candidate : crafting.getCraftables(key -> key.getId().getNamespace().equals(resource.namespace())
                && key.getId().getPath().equals(resource.path()))) {
            if (Ae2StoragePlatform.typeId(candidate.getType()).equals(resource.type())
                    && java.util.Objects.equals(Ae2StoragePlatform.variant(candidate), resource.variant())) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Acts as the requesting player, like the crafting terminal: CPU selection modes and AE2's job
     * notifications then work as in game. Offline players are represented by a fake player with their profile.
     */
    private IActionSource actionSource(PlayerProfile requester, Level level, IGrid grid) {
        Player player = server.getPlayerList().getPlayer(requester.uuid());
        if (player == null && level instanceof ServerLevel serverLevel) {
            player = FakePlayerFactory.get(serverLevel, new GameProfile(requester.uuid(), requester.name()));
        }
        IActionHost host = grid::getPivot;
        return player == null ? IActionSource.ofMachine(host) : IActionSource.ofPlayer(player, host);
    }

    private final class Ae2Calculation implements Calculation {
        private final Future<ICraftingPlan> future;

        private Ae2Calculation(Future<ICraftingPlan> future) {
            this.future = future;
        }

        @Override
        public boolean isDone() {
            return future.isDone();
        }

        /** The finished plan, or {@code null}. Never blocks. */
        ICraftingPlan planOrNull() {
            if (!future.isDone() || future.isCancelled()) {
                return null;
            }
            try {
                return future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (ExecutionException e) {
                return null;
            }
        }

        @Override
        public PlanSummary summary() {
            ICraftingPlan plan = planOrNull();
            if (plan == null) {
                throw new MeccException(ErrorCode.CRAFT_CALCULATION_FAILED, "The crafting calculation failed");
            }
            // The plan is immutable once calculated, so converting it here, off the server thread, is safe.
            Map<AEKey, long[]> rows = new LinkedHashMap<>();
            add(rows, plan.usedItems(), 0);
            add(rows, plan.missingItems(), 2);
            for (Map.Entry<IPatternDetails, Long> pattern : plan.patternTimes().entrySet()) {
                for (GenericStack output : pattern.getKey().getOutputs()) {
                    rows.computeIfAbsent(output.what(), key -> new long[3])[1] += output.amount() * pattern.getValue();
                }
            }
            add(rows, plan.emittedItems(), 1);
            List<PlanEntry> entries = new ArrayList<>(rows.size());
            rows.forEach((key, amounts) -> entries.add(new PlanEntry(storage.describe(key), amounts[0], amounts[1], amounts[2])));
            GenericStack output = plan.finalOutput();
            return new PlanSummary(storage.describe(output.what()), output.amount(), !plan.simulation(), plan.bytes(),
                    plan.multiplePaths(), entries);
        }

        private static void add(Map<AEKey, long[]> rows, KeyCounter counter, int column) {
            for (var entry : counter) {
                rows.computeIfAbsent(entry.getKey(), key -> new long[3])[column] += entry.getLongValue();
            }
        }

        @Override
        public void cancel() {
            future.cancel(true);
        }
    }

    // --- submission ---------------------------------------------------------------------------------

    @Override
    @ServerThreadOnly
    public SubmitOutcome submit(String gridKey, Calculation calculation, String cpuId, PlayerProfile requester) {
        requireServerThread();
        IGrid grid = grid(gridKey);
        ICraftingPlan plan = calculation instanceof Ae2Calculation ae2 ? ae2.planOrNull() : null;
        if (plan == null) {
            return SubmitOutcome.rejected("CRAFT_REJECTED", Map.of());
        }
        if (plan.simulation()) {
            return SubmitOutcome.rejected("PLAN_INCOMPLETE", Map.of());
        }
        ICraftingService crafting = grid.getCraftingService();
        ICraftingCPU target = null;
        if (cpuId != null) {
            target = findCpu(grid, cpuId);
            if (target == null) {
                return SubmitOutcome.rejected("CPU_NOT_FOUND", Map.of());
            }
        }
        Set<ICraftingCPU> busyBefore = new HashSet<>();
        for (ICraftingCPU cpu : crafting.getCpus()) {
            if (cpu.isBusy()) {
                busyBefore.add(cpu);
            }
        }
        IGridNode pivot = grid.getPivot();
        IActionSource source = actionSource(requester, pivot == null ? null : pivot.getLevel(), grid);
        ICraftingSubmitResult result = crafting.submitJob(plan, null, target, true, source);
        if (!result.successful()) {
            return rejection(result);
        }

        ICraftingCPU running = target;
        if (running == null) {
            // Submission is synchronous: the CPU that just became busy with this output took the job.
            for (ICraftingCPU cpu : crafting.getCpus()) {
                CraftingJobStatus status = cpu.isBusy() && !busyBefore.contains(cpu) ? cpu.getJobStatus() : null;
                if (status != null && status.crafting() != null && status.crafting().what().equals(plan.finalOutput().what())) {
                    running = cpu;
                    break;
                }
            }
        }
        if (running == null) {
            LOGGER.warn("AE2 accepted a crafting job but ME Control Center could not tell which CPU took it");
            return SubmitOutcome.started(null, null, null);
        }
        ICraftingLink link = link(running);
        String jobId = link == null ? null : link.getCraftingID().toString();
        if (jobId != null && running instanceof CraftingCPUCluster cluster) {
            retained.put(jobId, new RetainedJob(link, cluster));
        }
        return SubmitOutcome.started(cpuId(running), running.getName() == null ? null : ComponentText.of(running.getName()),
                jobId);
    }

    private static SubmitOutcome rejection(ICraftingSubmitResult result) {
        if (result.errorCode() == null) {
            return SubmitOutcome.rejected("CRAFT_REJECTED", Map.of());
        }
        return switch (result.errorCode()) {
            case INCOMPLETE_PLAN -> SubmitOutcome.rejected("PLAN_INCOMPLETE", Map.of());
            case NO_CPU_FOUND -> SubmitOutcome.rejected("NO_CRAFTING_CPU", Map.of());
            case NO_SUITABLE_CPU_FOUND -> {
                Map<String, Object> details = new HashMap<>();
                if (result.errorDetail() instanceof UnsuitableCpus unsuitable) {
                    details.put("offline", unsuitable.offline());
                    details.put("busy", unsuitable.busy());
                    details.put("tooSmall", unsuitable.tooSmall());
                    details.put("excluded", unsuitable.excluded());
                }
                yield SubmitOutcome.rejected("NO_SUITABLE_CPU", details);
            }
            case CPU_BUSY -> SubmitOutcome.rejected("CPU_BUSY", Map.of());
            case CPU_OFFLINE -> SubmitOutcome.rejected("CPU_OFFLINE", Map.of());
            case CPU_TOO_SMALL -> SubmitOutcome.rejected("CPU_TOO_SMALL", Map.of());
            case MISSING_INGREDIENT -> SubmitOutcome.rejected("MISSING_INGREDIENTS", Map.of());
        };
    }

    // --- cancellation -------------------------------------------------------------------------------

    @Override
    @ServerThreadOnly
    public CancelOutcome cancel(String gridKey, String cpuId, String jobId, ResourceId expectedOutput) {
        requireServerThread();
        ICraftingCPU cpu = findCpu(grid(gridKey), cpuId);
        if (cpu == null) {
            return CancelOutcome.CPU_NOT_FOUND;
        }
        if (cpu instanceof CraftingCPUCluster) {
            ICraftingLink link = link(cpu);
            if (link == null || (jobId != null && !link.getCraftingID().toString().equals(jobId))) {
                return CancelOutcome.NOT_RUNNING;
            }
        } else {
            CraftingJobStatus status = cpu.getJobStatus();
            if (jobId != null || status == null || status.crafting() == null
                    || (expectedOutput != null && !storage.describe(status.crafting().what()).id().equals(expectedOutput))) {
                return CancelOutcome.NOT_RUNNING;
            }
        }
        cpu.cancelJob();
        return CancelOutcome.CANCELLED;
    }

    // --- helpers ------------------------------------------------------------------------------------

    private static IGrid grid(String gridKey) {
        return GridRegistry.find(gridKey).orElseThrow(() ->
                new MeccException(ErrorCode.NETWORK_OFFLINE, "The ME network is not loaded right now"));
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("CraftingPlatform methods that touch AE2 must run on the server thread");
        }
    }
}
