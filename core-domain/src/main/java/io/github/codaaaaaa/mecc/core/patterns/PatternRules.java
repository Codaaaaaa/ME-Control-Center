package io.github.codaaaaaa.mecc.core.patterns;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Structural checks every pattern definition must pass before the game is consulted: slot counts, resource
 * types, amounts, limits (spec sections 13-14). Whether a matching recipe exists is the platform's job.
 */
public final class PatternRules {
    /** AE2 processing patterns hold at most 81 inputs and 27 outputs; limits never exceed that. */
    public static final int HARD_MAX_INPUTS = 81;
    public static final int HARD_MAX_OUTPUTS = 27;
    /** Larger amounts are almost certainly typos and overflow some machines' inventories. */
    public static final long MAX_AMOUNT = 1L << 40;
    private static final Pattern RECIPE_ID = Pattern.compile("^[a-z0-9_.\\-]{1,64}:[a-z0-9_.\\-/]{1,256}$");

    private final int maxInputs;
    private final int maxOutputs;

    public PatternRules(int maxInputs, int maxOutputs) {
        this.maxInputs = Math.min(maxInputs, HARD_MAX_INPUTS);
        this.maxOutputs = Math.min(maxOutputs, HARD_MAX_OUTPUTS);
    }

    /** Empty when the definition is structurally valid. */
    public List<PatternIssue> check(PatternDefinition definition) {
        List<PatternIssue> issues = new ArrayList<>();
        PatternType type = definition.type();
        List<PatternStack> inputs = definition.inputs();

        int slots = type.fixedInputSlots();
        if (slots > 0 && inputs.size() != slots) {
            issues.add(new PatternIssue(PatternIssue.WRONG_SLOT_COUNT, "inputs",
                    type + " patterns have exactly " + slots + " input slots"));
            return issues;
        }
        if (type == PatternType.PROCESSING && inputs.size() > maxInputs) {
            issues.add(new PatternIssue(PatternIssue.TOO_MANY_INPUTS, "inputs",
                    "A processing pattern may have at most " + maxInputs + " inputs"));
        }
        if (definition.presentInputs().isEmpty()) {
            issues.add(new PatternIssue(PatternIssue.NO_INPUTS, "inputs", "Add at least one input"));
        }

        for (int i = 0; i < inputs.size(); i++) {
            PatternStack stack = inputs.get(i);
            String field = "inputs[" + i + "]";
            if (stack == null) {
                if (type == PatternType.SMITHING || type == PatternType.STONECUTTING) {
                    issues.add(new PatternIssue(PatternIssue.MISSING_INPUT, field, "This slot is required"));
                }
                continue;
            }
            if (type.recipeDriven()) {
                if (!stack.resource().type().equals("item")) {
                    issues.add(new PatternIssue(PatternIssue.NOT_AN_ITEM, field, "Only items can be used in this pattern type"));
                }
                if (stack.amount() != 1) {
                    issues.add(new PatternIssue(PatternIssue.AMOUNT_NOT_ONE, field,
                            "Each slot of this pattern type holds exactly one item"));
                }
            } else if (stack.amount() > MAX_AMOUNT) {
                issues.add(new PatternIssue(PatternIssue.AMOUNT_TOO_LARGE, field, "The amount is too large"));
            }
        }

        if (type == PatternType.PROCESSING) {
            List<PatternStack> outputs = definition.outputs();
            if (outputs.size() > maxOutputs) {
                issues.add(new PatternIssue(PatternIssue.TOO_MANY_OUTPUTS, "outputs",
                        "A processing pattern may have at most " + maxOutputs + " outputs"));
            }
            if (outputs.isEmpty() || outputs.get(0) == null) {
                issues.add(new PatternIssue(PatternIssue.MISSING_PRIMARY_OUTPUT, "outputs[0]",
                        "The first output is the primary output and is required"));
            }
            for (int i = 0; i < outputs.size(); i++) {
                PatternStack stack = outputs.get(i);
                if (stack != null && stack.amount() > MAX_AMOUNT) {
                    issues.add(new PatternIssue(PatternIssue.AMOUNT_TOO_LARGE, "outputs[" + i + "]", "The amount is too large"));
                }
            }
        }

        String recipeId = definition.recipeId();
        if (recipeId != null && !RECIPE_ID.matcher(recipeId).matches()) {
            issues.add(new PatternIssue(PatternIssue.INVALID_RECIPE_ID, "recipeId", "Not a valid recipe ID"));
        } else if (recipeId == null && type == PatternType.STONECUTTING) {
            issues.add(new PatternIssue(PatternIssue.RECIPE_REQUIRED, "recipeId", "Choose which stonecutter recipe to use"));
        }
        return issues;
    }

    /**
     * The definition in canonical form: outputs dropped for recipe-driven types, trailing empty processing
     * slots removed, flags that do not apply cleared. Equal patterns then compare and store equally.
     */
    public static PatternDefinition normalize(PatternDefinition definition) {
        PatternType type = definition.type();
        if (type.recipeDriven()) {
            return new PatternDefinition(type, definition.inputs(), List.of(), definition.substitutes(),
                    type == PatternType.CRAFTING && definition.fluidSubstitutes(), definition.recipeId());
        }
        return new PatternDefinition(type, trimTrailing(definition.inputs()), trimTrailing(definition.outputs()), false,
                false, null);
    }

    private static List<PatternStack> trimTrailing(List<PatternStack> stacks) {
        int end = stacks.size();
        while (end > 0 && stacks.get(end - 1) == null) {
            end--;
        }
        return stacks.subList(0, end);
    }
}
