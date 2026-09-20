package io.github.codaaaaaa.mecc.core.config;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code [automation]} (spec section 25). Auto Restock is opt-in: it stays off until a server admin enables it
 * here, so upgrading the mod never starts crafting on its own.
 *
 * @param autoRestockEnabled      global switch; when false no restock rule ever submits
 * @param checkIntervalSeconds    how often rules are compared against stock
 * @param maxActiveJobsPerNetwork automation jobs that may run at the same time on one network
 * @param maxRulesPerNetwork      restock rules one network may have
 */
public record AutomationConfig(boolean autoRestockEnabled, int checkIntervalSeconds, int maxActiveJobsPerNetwork,
                               int maxRulesPerNetwork) {

    public static AutomationConfig defaults() {
        return new AutomationConfig(false, 60, 2, 50);
    }

    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (checkIntervalSeconds < 15 || checkIntervalSeconds > 3600) {
            problems.add("automation.check_interval_seconds must be between 15 and 3600, got " + checkIntervalSeconds);
        }
        if (maxActiveJobsPerNetwork < 1 || maxActiveJobsPerNetwork > 64) {
            problems.add("automation.max_active_jobs_per_network must be between 1 and 64, got " + maxActiveJobsPerNetwork);
        }
        if (maxRulesPerNetwork < 1 || maxRulesPerNetwork > 500) {
            problems.add("automation.max_rules_per_network must be between 1 and 500, got " + maxRulesPerNetwork);
        }
        return problems;
    }
}
