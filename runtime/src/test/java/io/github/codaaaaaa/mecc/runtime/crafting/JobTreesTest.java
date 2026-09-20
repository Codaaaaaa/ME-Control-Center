package io.github.codaaaaaa.mecc.runtime.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.codaaaaaa.mecc.core.crafting.CraftingViews.StepStatus;
import io.github.codaaaaaa.mecc.core.resources.ResourceDescriptor;
import io.github.codaaaaaa.mecc.core.resources.ResourceId;
import io.github.codaaaaaa.mecc.core.resources.ResourceText;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform.JobDetail;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform.JobTask;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform.ResourceAmount;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class JobTreesTest {
    private static final ResourceDescriptor PROCESSOR = item("processor");
    private static final ResourceDescriptor CIRCUIT = item("circuit");
    private static final ResourceDescriptor SILICON = item("silicon");
    private static final ResourceDescriptor RAW = item("raw");

    private static ResourceDescriptor item(String path) {
        return new ResourceDescriptor(ResourceId.of("item", "test", path), "item.test." + path,
                ResourceText.literal(path), "test", null, "item/test/" + path);
    }

    private static ResourceAmount amount(ResourceDescriptor resource, long amount) {
        return new ResourceAmount(resource, amount);
    }

    /** processor <- 2 circuit + 1 silicon (from storage); circuit <- 1 raw (from storage). */
    private static JobTask processor(long remaining, boolean machineAvailable) {
        return new JobTask("p-processor", List.of(amount(PROCESSOR, 1)),
                List.of(amount(CIRCUIT, 2), amount(SILICON, 1)), remaining, machineAvailable, List.of("m-assembler"));
    }

    private static JobTask circuit(long remaining) {
        return new JobTask("p-circuit", List.of(amount(CIRCUIT, 1)), List.of(amount(RAW, 1)), remaining, true, List.of());
    }

    private static JobDetail detail(List<JobTask> tasks, List<ResourceAmount> stored, List<ResourceAmount> inMachines) {
        return new JobDetail("job", PROCESSOR, 4, Duration.ofSeconds(1), tasks, stored, inMachines);
    }

    @Test
    void stepsFollowWhatTheCpuHoldsAndWaitsFor() {
        JobTrees.Memory memory = new JobTrees.Memory(false);
        JobDetail start = detail(List.of(processor(4, true), circuit(8)),
                List.of(amount(SILICON, 4), amount(RAW, 6)), List.of(amount(CIRCUIT, 2)));
        memory.see(start.tasks());
        JobTrees.Tree tree = JobTrees.build(memory, start);
        assertEquals("p-processor", tree.root().id());
        assertEquals(StepStatus.WAITING_INPUTS, tree.root().status(), "no circuits in the CPU yet");
        JobTrees.Step circuitStep = tree.root().children().get(0);
        assertEquals(StepStatus.CRAFTING, circuitStep.status());
        assertEquals(2, circuitStep.inMachines());
        assertEquals(StepStatus.FROM_STORAGE, circuitStep.children().get(0).status());
        assertEquals(StepStatus.FROM_STORAGE, tree.root().children().get(1).status(), "silicon");

        // Circuits are done (the task vanished); the processor has its inputs but every machine is busy.
        JobDetail later = detail(List.of(processor(4, false)), List.of(amount(SILICON, 4), amount(CIRCUIT, 8)), List.of());
        memory.see(later.tasks());
        tree = JobTrees.build(memory, later);
        assertEquals(StepStatus.WAITING_MACHINE, tree.root().status());
        assertEquals(StepStatus.DONE, tree.root().children().get(0).status(), "remembered from the first sighting");
        assertEquals(8, tree.root().children().get(0).runs());
        assertEquals(1, tree.counts().get(StepStatus.DONE));

        JobDetail ready = detail(List.of(processor(4, true)), List.of(amount(SILICON, 4), amount(CIRCUIT, 8)), List.of());
        assertEquals(StepStatus.READY, JobTrees.build(memory, ready).root().status());
    }

    @Test
    void recipeLoopsEndInsteadOfRecursing() {
        // a <- b, b <- a: e.g. a pattern that returns a catalyst.
        JobTask a = new JobTask("a", List.of(amount(PROCESSOR, 1)), List.of(amount(CIRCUIT, 1)), 1, true, List.of());
        JobTask b = new JobTask("b", List.of(amount(CIRCUIT, 1)), List.of(amount(PROCESSOR, 1)), 1, true, List.of());
        JobTrees.Memory memory = new JobTrees.Memory(true);
        JobDetail detail = detail(List.of(a, b), List.of(), List.of());
        memory.see(detail.tasks());
        JobTrees.Tree tree = JobTrees.build(memory, detail);
        assertEquals("b", tree.root().children().get(0).id());
        assertEquals(StepStatus.FROM_STORAGE, tree.root().children().get(0).children().get(0).status());
        assertTrue(tree.partial());
    }
}
