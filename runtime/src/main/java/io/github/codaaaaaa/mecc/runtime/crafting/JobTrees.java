package io.github.codaaaaaa.mecc.runtime.crafting;

import io.github.codaaaaaa.mecc.core.crafting.CraftingViews.JobTreeView;
import io.github.codaaaaaa.mecc.core.crafting.CraftingViews.StepStatus;
import io.github.codaaaaaa.mecc.core.crafting.CraftingViews.StepView;
import io.github.codaaaaaa.mecc.core.resources.ResourceDescriptor;
import io.github.codaaaaaa.mecc.core.resources.ResourceId;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform.JobDetail;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform.JobTask;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform.ResourceAmount;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Crafting trees of running jobs (spec section 12). The crafting system only reports the patterns a job still has to
 * run; finished ones vanish. So every job's patterns are remembered from the first time it is seen, and a remembered
 * pattern that is gone has finished. Thread-safe.
 *
 * <p>Where each step stands follows from what the CPU reports: results it waits for from machines (crafting), inputs
 * it already holds (ready, or waiting for a busy machine), or neither (waiting for the steps below).
 */
public final class JobTrees {
    /** Jobs remembered at once; the least recently viewed is forgotten first. */
    static final int MAX_JOBS = 64;
    /** Steps shown in one tree; a pattern feeding many others is repeated under each, so trees can grow. */
    static final int MAX_STEPS = 2_000;
    /** A job first seen later than this after it started may have finished steps ME Control Center never saw. */
    static final Duration PARTIAL_AFTER = Duration.ofSeconds(10);

    private final ResourceLabels labels;
    private final Supplier<String> assetVersion;
    private final Map<String, Memory> jobs = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Memory> eldest) {
            return size() > MAX_JOBS;
        }
    };

    /** Every pattern seen for a job, and the most runs it had left at any sighting. */
    static final class Memory {
        private final Map<String, JobTask> tasks = new LinkedHashMap<>();
        private final Map<String, Long> runs = new HashMap<>();
        private final boolean partial;

        Memory(boolean partial) {
            this.partial = partial;
        }

        void see(List<JobTask> current) {
            for (JobTask task : current) {
                tasks.putIfAbsent(task.id(), task);
                runs.merge(task.id(), task.remaining(), Math::max);
            }
        }
    }

    public JobTrees(ResourceLabels labels, Supplier<String> assetVersion) {
        this.labels = labels;
        this.assetVersion = assetVersion;
    }

    public JobTreeView view(String cpuId, JobDetail detail, String locale) {
        String key = detail.jobId() != null ? detail.jobId()
                : cpuId + "|" + detail.output().id() + "|" + detail.amount();
        Tree tree;
        synchronized (jobs) {
            Memory memory = jobs.computeIfAbsent(key, ignored ->
                    new Memory(detail.elapsed() != null && detail.elapsed().compareTo(PARTIAL_AFTER) > 0));
            memory.see(detail.tasks());
            tree = build(memory, detail);
        }
        return new JobTreeView(cpuId, detail.jobId(), labels.label(detail.output(), locale), detail.amount(),
                detail.elapsed() == null ? null : detail.elapsed().toMillis(), tree.partial, present(tree.root, locale),
                tree.counts, assetVersion.get());
    }

    /** A step before labels are applied. */
    record Step(String id, ResourceDescriptor resource, long perRun, long runs, long remaining, StepStatus status,
                long inMachines, long stored, List<String> machineIds, List<Step> children) {
    }

    record Tree(Step root, Map<StepStatus, Integer> counts, boolean partial) {
    }

    /** The tree for what the memory holds and the CPU reports now. Pure. */
    static Tree build(Memory memory, JobDetail detail) {
        Map<String, JobTask> current = new HashMap<>();
        detail.tasks().forEach(task -> current.put(task.id(), task));
        Map<ResourceId, Long> stored = amounts(detail.stored());
        Map<ResourceId, Long> inMachines = amounts(detail.inMachines());

        Map<String, StepStatus> statuses = new LinkedHashMap<>();
        Map<ResourceId, List<String>> producers = new HashMap<>();
        memory.tasks.forEach((id, task) -> {
            statuses.put(id, status(task, current.get(id), stored, inMachines));
            for (ResourceAmount output : task.outputs()) {
                producers.computeIfAbsent(output.resource().id(), ignored -> new ArrayList<>()).add(id);
            }
        });
        Map<StepStatus, Integer> counts = new EnumMap<>(StepStatus.class);
        statuses.values().forEach(status -> counts.merge(status, 1, Integer::sum));

        Builder builder = new Builder(memory, current, statuses, producers, stored, inMachines);
        List<String> top = producers.getOrDefault(detail.output().id(), List.of());
        Step root = top.isEmpty()
                ? new Step(detail.output().id().toString(), detail.output(), detail.amount(), 1, 0, StepStatus.DONE, 0,
                        stored.getOrDefault(detail.output().id(), 0L), List.of(), List.of())
                : builder.step(top.get(0), new HashSet<>());
        return new Tree(root, counts, memory.partial);
    }

    static StepStatus status(JobTask remembered, JobTask current, Map<ResourceId, Long> stored,
                             Map<ResourceId, Long> inMachines) {
        for (ResourceAmount output : remembered.outputs()) {
            if (inMachines.getOrDefault(output.resource().id(), 0L) > 0) {
                return StepStatus.CRAFTING;
            }
        }
        if (current == null || current.remaining() <= 0) {
            return StepStatus.DONE;
        }
        for (ResourceAmount input : remembered.inputs()) {
            if (stored.getOrDefault(input.resource().id(), 0L) < input.amount()) {
                return StepStatus.WAITING_INPUTS;
            }
        }
        return current.machineAvailable() ? StepStatus.READY : StepStatus.WAITING_MACHINE;
    }

    private static final class Builder {
        private final Memory memory;
        private final Map<String, JobTask> current;
        private final Map<String, StepStatus> statuses;
        private final Map<ResourceId, List<String>> producers;
        private final Map<ResourceId, Long> stored;
        private final Map<ResourceId, Long> inMachines;
        private int steps;

        private Builder(Memory memory, Map<String, JobTask> current, Map<String, StepStatus> statuses,
                        Map<ResourceId, List<String>> producers, Map<ResourceId, Long> stored,
                        Map<ResourceId, Long> inMachines) {
            this.memory = memory;
            this.current = current;
            this.statuses = statuses;
            this.producers = producers;
            this.stored = stored;
            this.inMachines = inMachines;
        }

        /** @param path patterns above this one, so a recipe loop ends instead of recursing forever */
        private Step step(String id, Set<String> path) {
            steps++;
            JobTask task = memory.tasks.get(id);
            ResourceAmount primary = task.outputs().get(0);
            List<Step> children = new ArrayList<>();
            path.add(id);
            for (ResourceAmount input : task.inputs()) {
                if (steps >= MAX_STEPS) {
                    break;
                }
                ResourceId resource = input.resource().id();
                List<String> makers = producers.getOrDefault(resource, List.of()).stream()
                        .filter(maker -> !path.contains(maker)).toList();
                if (makers.isEmpty()) {
                    steps++;
                    children.add(new Step(resource.toString(), input.resource(), input.amount(), 0, 0,
                            StepStatus.FROM_STORAGE, 0, stored.getOrDefault(resource, 0L), List.of(), List.of()));
                } else {
                    for (String maker : makers) {
                        children.add(step(maker, path));
                    }
                }
            }
            path.remove(id);
            JobTask now = current.get(id);
            return new Step(id, primary.resource(), primary.amount(), memory.runs.getOrDefault(id, 0L),
                    now == null ? 0 : now.remaining(), statuses.get(id),
                    inMachines.getOrDefault(primary.resource().id(), 0L),
                    stored.getOrDefault(primary.resource().id(), 0L),
                    (now == null ? task : now).machineIds(), children);
        }
    }

    private static Map<ResourceId, Long> amounts(List<ResourceAmount> amounts) {
        Map<ResourceId, Long> map = new HashMap<>();
        amounts.forEach(amount -> map.merge(amount.resource().id(), amount.amount(), Long::sum));
        return map;
    }

    private StepView present(Step step, String locale) {
        return new StepView(step.id(), labels.label(step.resource(), locale), step.perRun(), step.runs(), step.remaining(),
                step.status(), step.inMachines(), step.stored(), step.machineIds(),
                step.children().stream().map(child -> present(child, locale)).toList());
    }
}
