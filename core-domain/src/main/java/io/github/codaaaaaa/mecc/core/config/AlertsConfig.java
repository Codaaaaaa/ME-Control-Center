package io.github.codaaaaaa.mecc.core.config;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code [alerts]} (spec section 23).
 *
 * @param checkIntervalSeconds       how often condition rules are evaluated
 * @param webhooksEnabled            whether players may send alerts to Discord and generic webhooks
 * @param allowPrivateWebhookTargets allow webhooks to loopback and LAN addresses; off protects the server's own network
 * @param maxRulesPerUser            alert rules one player may keep across all networks
 */
public record AlertsConfig(boolean enabled, int checkIntervalSeconds, boolean webhooksEnabled,
                           boolean allowPrivateWebhookTargets, int maxRulesPerUser) {

    public static AlertsConfig defaults() {
        return new AlertsConfig(true, 15, true, false, 50);
    }

    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (checkIntervalSeconds < 5 || checkIntervalSeconds > 300) {
            problems.add("alerts.check_interval_seconds must be between 5 and 300, got " + checkIntervalSeconds);
        }
        if (maxRulesPerUser < 1 || maxRulesPerUser > 1000) {
            problems.add("alerts.max_rules_per_user must be between 1 and 1000, got " + maxRulesPerUser);
        }
        return problems;
    }
}
