package io.github.codaaaaaa.mecc.core.config;

import io.github.codaaaaaa.mecc.core.patterns.PatternRules;
import java.util.ArrayList;
import java.util.List;

/**
 * Pattern Studio settings ({@code [patterns]} section).
 *
 * @param maxPatternInputs  most inputs of one processing pattern (spec section 46, {@code max_pattern_inputs})
 * @param maxPatternOutputs most outputs of one processing pattern ({@code max_pattern_outputs})
 * @param maxDraftsPerUser  pattern drafts one player may keep
 */
public record PatternsConfig(int maxPatternInputs, int maxPatternOutputs, int maxDraftsPerUser) {

    public static PatternsConfig defaults() {
        return new PatternsConfig(PatternRules.HARD_MAX_INPUTS, PatternRules.HARD_MAX_OUTPUTS, 200);
    }

    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (maxPatternInputs < 1 || maxPatternInputs > PatternRules.HARD_MAX_INPUTS) {
            problems.add("patterns.max_pattern_inputs must be between 1 and " + PatternRules.HARD_MAX_INPUTS
                    + ", got " + maxPatternInputs);
        }
        if (maxPatternOutputs < 1 || maxPatternOutputs > PatternRules.HARD_MAX_OUTPUTS) {
            problems.add("patterns.max_pattern_outputs must be between 1 and " + PatternRules.HARD_MAX_OUTPUTS
                    + ", got " + maxPatternOutputs);
        }
        if (maxDraftsPerUser < 1 || maxDraftsPerUser > 10_000) {
            problems.add("patterns.max_drafts_per_user must be between 1 and 10000, got " + maxDraftsPerUser);
        }
        return problems;
    }
}
