package io.github.codaaaaaa.mecc.core.alerts;

import io.github.codaaaaaa.mecc.core.resources.ResourceViews.ResourceLabel;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** API response models for alerts (spec section 23). */
public final class AlertViews {
    private AlertViews() {
    }

    /** @param active whether the condition holds right now (condition rules) */
    public record RuleView(UUID id, UUID networkId, AlertType type, ResourceLabel resource, Long threshold,
                           Integer windowMinutes, int cooldownMinutes, boolean enabled, boolean active, Instant notifiedAt,
                           Instant createdAt) {
    }

    public record RuleList(List<RuleView> rules, int limit, String assetVersion) {
    }

    /** @param networkName current name of the network, or {@code null} */
    public record EventView(long id, UUID ruleId, UUID networkId, String networkName, AlertType type,
                            AlertEvent.Kind kind, Instant at, ResourceLabel resource, Long value, Long threshold,
                            UUID orderId) {
    }

    /** @param nextBefore pass as {@code before} for older events, or {@code null} at the end */
    public record EventPage(List<EventView> events, Long nextBefore, String assetVersion) {
    }

    /** @param webhooksEnabled whether the server allows webhooks at all */
    public record SettingsView(String discordWebhookUrl, String webhookUrl, boolean webhooksEnabled) {
    }

    /** Per channel ({@code discord}, {@code webhook}): {@code OK} or why it failed. */
    public record TestResult(Map<String, String> channels) {
    }
}
