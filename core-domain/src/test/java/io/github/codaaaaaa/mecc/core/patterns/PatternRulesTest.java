package io.github.codaaaaaa.mecc.core.patterns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.codaaaaaa.mecc.core.resources.ResourceId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class PatternRulesTest {
    private static final PatternStack PLANK = new PatternStack(ResourceId.of("item", "minecraft", "oak_planks"), 1);
    private static final PatternStack WATER = new PatternStack(ResourceId.of("fluid", "minecraft", "water"), 1000);
    private final PatternRules rules = new PatternRules(4, 2);

    @Test
    void craftingNeedsNineItemCellsOfOne() {
        List<PatternStack> grid = new ArrayList<>(Collections.nCopies(9, (PatternStack) null));
        assertEquals(List.of(PatternIssue.NO_INPUTS), codes(crafting(grid)));

        grid.set(4, PLANK);
        assertTrue(codes(crafting(grid)).isEmpty());

        grid.set(0, WATER);
        grid.set(1, new PatternStack(PLANK.resource(), 2));
        assertEquals(List.of(PatternIssue.NOT_AN_ITEM, PatternIssue.AMOUNT_NOT_ONE, PatternIssue.AMOUNT_NOT_ONE), codes(crafting(grid)));
        assertEquals(List.of(PatternIssue.WRONG_SLOT_COUNT), codes(crafting(List.of(PLANK))));
    }

    @Test
    void processingNeedsAPrimaryOutputAndRespectsLimits() {
        PatternDefinition ok = new PatternDefinition(PatternType.PROCESSING, List.of(WATER), List.of(PLANK), false, false, null);
        assertTrue(codes(ok).isEmpty(), "fluids are fine in processing patterns");

        PatternDefinition noPrimary = new PatternDefinition(PatternType.PROCESSING, List.of(WATER),
                Arrays.asList(null, PLANK), false, false, null);
        assertEquals(List.of(PatternIssue.MISSING_PRIMARY_OUTPUT), codes(noPrimary));

        PatternDefinition tooBig = new PatternDefinition(PatternType.PROCESSING, List.of(WATER, WATER, WATER, WATER, WATER),
                List.of(PLANK, PLANK, PLANK), false, false, null);
        assertEquals(List.of(PatternIssue.TOO_MANY_INPUTS, PatternIssue.TOO_MANY_OUTPUTS), codes(tooBig));

        PatternDefinition huge = new PatternDefinition(PatternType.PROCESSING,
                List.of(new PatternStack(WATER.resource(), PatternRules.MAX_AMOUNT + 1)), List.of(PLANK), false, false, null);
        assertEquals(List.of(PatternIssue.AMOUNT_TOO_LARGE), codes(huge));
    }

    @Test
    void smithingAndStonecuttingNeedEverySlotAndStonecuttingARecipe() {
        PatternDefinition smithing = new PatternDefinition(PatternType.SMITHING, Arrays.asList(PLANK, null, PLANK), List.of(),
                false, false, null);
        assertEquals(List.of(PatternIssue.MISSING_INPUT), codes(smithing));

        PatternDefinition stonecutting = new PatternDefinition(PatternType.STONECUTTING, List.of(PLANK), List.of(), false,
                false, null);
        assertEquals(List.of(PatternIssue.RECIPE_REQUIRED), codes(stonecutting));
        PatternDefinition badId = new PatternDefinition(PatternType.STONECUTTING, List.of(PLANK), List.of(), false, false,
                "Not A Recipe");
        assertEquals(List.of(PatternIssue.INVALID_RECIPE_ID), codes(badId));
    }

    @Test
    void normalizeDropsWhatDoesNotApply() {
        PatternDefinition crafting = new PatternDefinition(PatternType.CRAFTING, Collections.nCopies(9, PLANK), List.of(PLANK),
                true, true, "minecraft:x");
        PatternDefinition normalized = PatternRules.normalize(crafting);
        assertTrue(normalized.outputs().isEmpty(), "recipes decide crafting outputs");
        assertTrue(normalized.fluidSubstitutes());

        PatternDefinition processing = new PatternDefinition(PatternType.PROCESSING, Arrays.asList(WATER, null, null),
                Arrays.asList(PLANK, null), true, true, "minecraft:x");
        PatternDefinition trimmed = PatternRules.normalize(processing);
        assertEquals(List.of(WATER), trimmed.inputs());
        assertEquals(List.of(PLANK), trimmed.outputs());
        assertEquals(new PatternDefinition(PatternType.PROCESSING, List.of(WATER), List.of(PLANK), false, false, null), trimmed);
    }

    private List<String> codes(PatternDefinition definition) {
        return rules.check(definition).stream().map(PatternIssue::code).toList();
    }

    private static PatternDefinition crafting(List<PatternStack> grid) {
        return new PatternDefinition(PatternType.CRAFTING, grid, List.of(), false, false, null);
    }
}
