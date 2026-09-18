package io.github.codaaaaaa.mecc.core.alerts;

import io.github.codaaaaaa.mecc.core.alerts.AlertViews.EventPage;
import io.github.codaaaaaa.mecc.core.alerts.AlertViews.RuleList;
import io.github.codaaaaaa.mecc.core.alerts.AlertViews.RuleView;
import io.github.codaaaaaa.mecc.core.alerts.AlertViews.SettingsView;
import io.github.codaaaaaa.mecc.core.alerts.AlertViews.TestResult;
import io.github.codaaaaaa.mecc.core.auth.Session;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Personal alert rules, their events, and notification channels (spec section 23). Rules need
 * {@code VIEW_NETWORK} on their network; everything else is the caller's own.
 */
public interface AlertService {

    /**
     * @param resourceId text form of the resource, for types that take one; ignored by {@link #updateRule}
     * @param threshold     raw amount, percent, or minutes, as {@link AlertType#threshold()} says
     * @param windowMinutes for percentage-change rules
     */
    record RuleInput(AlertType type, String resourceId, Long threshold, Integer windowMinutes, Integer cooldownMinutes,
                     Boolean enabled) {
    }

    record SettingsInput(String discordWebhookUrl, String webhookUrl, String locale) {
    }

    CompletionStage<RuleList> rules(Session session, UUID networkId, String locale);

    /**
     * Fails with {@code VALIDATION_FAILED}, {@code RESOURCE_NOT_FOUND} when the network neither stores nor can craft the
     * resource, or {@code CONFLICT} beyond the per-player limit.
     */
    CompletionStage<RuleView> createRule(Session session, UUID networkId, RuleInput input, String locale);

    /** Changes threshold, window, cooldown, and enabled; a {@code null} field is left as it is. */
    CompletionStage<RuleView> updateRule(Session session, UUID ruleId, RuleInput input, String locale);

    CompletionStage<Void> deleteRule(Session session, UUID ruleId);

    /** @param networkId only this network, or {@code null} for every network */
    CompletionStage<EventPage> events(Session session, UUID networkId, Long before, int limit, String locale);

    CompletionStage<SettingsView> settings(Session session);

    /** Fails with {@code VALIDATION_FAILED} for a webhook address that is not allowed. */
    CompletionStage<SettingsView> updateSettings(Session session, SettingsInput input);

    /** Sends a test message to every configured channel. */
    CompletionStage<TestResult> test(Session session);
}
