package io.github.codaaaaaa.mecc.platform;

import io.github.codaaaaaa.mecc.core.networks.BlockLocation;
import io.github.codaaaaaa.mecc.core.resources.ResourceDescriptor;
import io.github.codaaaaaa.mecc.core.resources.ResourceId;
import io.github.codaaaaaa.mecc.core.resources.ResourceText;
import io.github.codaaaaaa.mecc.core.users.PlayerProfile;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Autocrafting on an ME network (spec section 43, {@code CraftingPlatform}).
 *
 * <p>Jobs are identified by an opaque {@code jobId} that survives CPU rebuilds and server restarts where
 * the platform allows it. CPU identifiers are opaque and URL-safe.
 */
public interface CraftingPlatform {

    /**
     * Copies the state of every crafting CPU of a grid, and reports what became of each watched job.
     *
     * @param watchedJobIds jobs ME Control Center is tracking on this grid
     * @throws io.github.codaaaaaa.mecc.core.error.MeccException {@code NETWORK_OFFLINE} if the grid no longer exists
     */
    @ServerThreadOnly
    CpuCapture captureCpus(String gridKey, Set<String> watchedJobIds);

    /**
     * Starts calculating a plan. The heavy work must not run inside this call.
     *
     * @throws io.github.codaaaaaa.mecc.core.error.MeccException {@code NOT_CRAFTABLE} when no pattern produces the resource
     */
    @ServerThreadOnly
    Calculation beginCalculation(String gridKey, ResourceId resource, long amount, PlayerProfile requester);

    /**
     * Hands a finished, complete plan to the crafting system. Must revalidate everything (ingredients, CPU
     * state) itself; never throws for a rejection, which is reported in the outcome instead.
     *
     * @param cpuId CPU to use, or {@code null} to let the crafting system choose
     */
    @ServerThreadOnly
    SubmitOutcome submit(String gridKey, Calculation calculation, String cpuId, PlayerProfile requester);

    /**
     * Cancels a job if, and only if, the CPU is still running it.
     *
     * @param jobId          expected job, or {@code null} for CPUs without job identifiers
     * @param expectedOutput for CPUs without job identifiers: the output the job must be crafting
     */
    @ServerThreadOnly
    CancelOutcome cancel(String gridKey, String cpuId, String jobId, ResourceId expectedOutput);

    /**
     * What a CPU's job is made of right now, for the crafting tree (spec section 12): every pattern still to run,
     * what the CPU holds, and what it waits for from machines. {@code null} when the CPU is idle, unknown, or of a type
     * whose job cannot be read.
     */
    @ServerThreadOnly
    JobDetail describeJob(String gridKey, String cpuId);

    /** An amount of a resource; raw units (millibuckets for fluids). */
    record ResourceAmount(ResourceDescriptor resource, long amount) {
        public ResourceAmount {
            Objects.requireNonNull(resource, "resource");
        }
    }

    /**
     * One pattern of a running job.
     *
     * @param id               identifies the pattern within the job, stable while it runs
     * @param outputs          what one run makes; the first is the primary output
     * @param inputs           what one run takes
     * @param remaining        runs still to push to machines
     * @param machineAvailable whether some provider holding the pattern could take a run now
     * @param machineIds       the machines holding the pattern, as {@code PatternPlatform.MachineState#id}
     */
    record JobTask(String id, List<ResourceAmount> outputs, List<ResourceAmount> inputs, long remaining,
                   boolean machineAvailable, List<String> machineIds) {
        public JobTask {
            outputs = List.copyOf(outputs);
            inputs = List.copyOf(inputs);
            machineIds = List.copyOf(machineIds);
        }
    }

    /**
     * @param stored     what the CPU holds: ingredients taken from storage and intermediate results
     * @param inMachines what the CPU waits for from machines it pushed patterns to
     */
    record JobDetail(String jobId, ResourceDescriptor output, long amount, Duration elapsed, List<JobTask> tasks,
                     List<ResourceAmount> stored, List<ResourceAmount> inMachines) {
        public JobDetail {
            Objects.requireNonNull(output, "output");
            tasks = List.copyOf(tasks);
            stored = List.copyOf(stored);
            inMachines = List.copyOf(inMachines);
        }
    }

    /** A running or finished crafting calculation. All methods are thread-safe. */
    interface Calculation {
        boolean isDone();

        /**
         * Describes the finished plan. Call off the server thread, only after {@link #isDone()}.
         *
         * @throws io.github.codaaaaaa.mecc.core.error.MeccException {@code CRAFT_CALCULATION_FAILED}
         */
        PlanSummary summary();

        /** Stops a calculation that is still running. */
        void cancel();
    }

    /**
     * @param output   what the plan crafts
     * @param amount   amount the plan crafts
     * @param complete {@code false} when ingredients are missing (a simulation only)
     * @param bytes    crafting storage the job needs
     * @param entries  every resource involved
     */
    record PlanSummary(ResourceDescriptor output, long amount, boolean complete, long bytes, boolean multiplePaths,
                       List<PlanEntry> entries) {
        public PlanSummary {
            Objects.requireNonNull(output, "output");
            entries = List.copyOf(entries);
        }
    }

    record PlanEntry(ResourceDescriptor resource, long stored, long toCraft, long missing) {
    }

    /**
     * @param capturedAt when the capture ran
     * @param cpus       every CPU of the grid
     * @param watched    fate of each watched job
     */
    record CpuCapture(Instant capturedAt, List<CpuState> cpus, Map<String, JobFate> watched) {
        public CpuCapture {
            cpus = List.copyOf(cpus);
            watched = Map.copyOf(watched);
        }
    }

    /**
     * @param id            opaque, URL-safe, stable while the CPU exists (across restarts where possible)
     * @param name          player-given name, or {@code null}
     * @param location      where the CPU is, or {@code null}
     * @param online        powered and connected, or {@code null} when unknown
     * @param selectionMode {@code ANY}, {@code PLAYER_ONLY}, {@code MACHINE_ONLY}
     * @param job           current job, or {@code null} when idle
     */
    record CpuState(String id, ResourceText name, BlockLocation location, boolean busy, Boolean online,
                    long storageBytes, int coProcessors, String selectionMode, JobState job) {
        public CpuState {
            Objects.requireNonNull(id, "id");
        }
    }

    /**
     * @param jobId    job identifier, or {@code null} when this CPU type has none
     * @param amount   requested amount of the final output
     * @param progress completed fraction 0-1 as reported by the crafting system, or {@code null}
     * @param elapsed  running time, or {@code null}
     * @param requesterUuid the player who requested the job (in game or through ME Control Center), or {@code null}
     * @param requesterName that player's name as the server knows it, or {@code null}
     */
    record JobState(String jobId, ResourceDescriptor output, long amount, Double progress, Duration elapsed,
                    UUID requesterUuid, String requesterName) {
        public JobState {
            Objects.requireNonNull(output, "output");
        }
    }

    /** What became of a watched job. */
    enum JobFate {
        /** It is running on a CPU of this grid. */
        RUNNING,
        COMPLETED,
        CANCELLED,
        /** It may still exist, but its CPU is not loaded or not part of this grid right now. */
        UNOBSERVABLE,
        /** Nothing is known about it. */
        NOT_FOUND
    }

    /**
     * @param errorCode {@code null} on success, else one of {@code NO_CRAFTING_CPU}, {@code NO_SUITABLE_CPU},
     *                  {@code CPU_BUSY}, {@code CPU_OFFLINE}, {@code CPU_TOO_SMALL}, {@code CPU_NOT_FOUND},
     *                  {@code MISSING_INGREDIENTS}, {@code PLAN_INCOMPLETE}, {@code CRAFT_REJECTED}
     * @param details   extra facts about a rejection
     */
    record SubmitOutcome(String cpuId, ResourceText cpuName, String jobId, String errorCode, Map<String, Object> details) {
        public SubmitOutcome {
            details = details == null ? Map.of() : Map.copyOf(details);
        }

        public static SubmitOutcome started(String cpuId, ResourceText cpuName, String jobId) {
            return new SubmitOutcome(cpuId, cpuName, jobId, null, Map.of());
        }

        public static SubmitOutcome rejected(String errorCode, Map<String, Object> details) {
            return new SubmitOutcome(null, null, null, errorCode, details);
        }

        public boolean success() {
            return errorCode == null;
        }
    }

    enum CancelOutcome {
        CANCELLED,
        /** The CPU is idle or running a different job. */
        NOT_RUNNING,
        CPU_NOT_FOUND
    }
}
