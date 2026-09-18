package io.github.codaaaaaa.mecc.runtime.alerts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.codaaaaaa.mecc.core.alerts.AlertEvent;
import io.github.codaaaaaa.mecc.core.alerts.AlertSettings;
import io.github.codaaaaaa.mecc.core.alerts.AlertViews.EventView;
import io.github.codaaaaaa.mecc.core.config.AlertsConfig;
import io.github.codaaaaaa.mecc.core.live.LiveEvent;
import io.github.codaaaaaa.mecc.core.persistence.DataStore;
import io.github.codaaaaaa.mecc.runtime.crafting.ResourceLabels;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers alert events (spec section 23): to the owner's open browser tabs (which may raise a browser notification)
 * and to their Discord and generic webhooks. Never runs on the server thread; webhook failures are logged, not
 * retried.
 */
public final class AlertNotifier {
    private static final Logger LOGGER = LoggerFactory.getLogger(AlertNotifier.class);
    static final Duration WEBHOOK_TIMEOUT = Duration.ofSeconds(10);
    private static final int DISCORD_MAX_CONTENT = 2000;

    private final DataStore store;
    private final AlertsConfig config;
    private final ResourceLabels labels;
    private final Function<UUID, String> networkNames;
    private final BiConsumer<UUID, LiveEvent> live;
    private final Executor workers;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;

    public AlertNotifier(DataStore store, AlertsConfig config, ResourceLabels labels, Function<UUID, String> networkNames,
                         BiConsumer<UUID, LiveEvent> live, Executor workers, Clock clock) {
        this.store = store;
        this.config = config;
        this.labels = labels;
        this.networkNames = networkNames;
        this.live = live;
        this.workers = workers;
        this.clock = clock;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER) // a redirect could lead past the address check
                .connectTimeout(Duration.ofSeconds(5))
                .executor(workers)
                .build();
    }

    EventView view(AlertEvent event, String locale) {
        return new EventView(event.id(), event.ruleId(), event.networkId(), networkNames.apply(event.networkId()),
                event.type(), event.kind(), event.at(), event.resource() == null ? null : labels.label(event.resource(), locale),
                event.value(), event.threshold(), event.orderId());
    }

    /** Pushes stored events to their owners. */
    void deliver(List<AlertEvent> events) {
        for (AlertEvent event : events) {
            store.read(repos -> repos.alerts().settings(event.playerUuid())).thenAccept(settings -> {
                String locale = settings.map(AlertSettings::locale).orElse("en_us");
                live.accept(event.playerUuid(), new LiveEvent(event.kind() == AlertEvent.Kind.TRIGGERED
                        ? LiveEvent.ALERT_TRIGGERED : LiveEvent.ALERT_RESOLVED, clock.instant(), event.networkId(),
                        view(event, locale)));
                if (config.webhooksEnabled() && settings.isPresent()) {
                    webhooks(settings.get(), event).forEach((channel, result) -> result.thenAccept(outcome -> {
                        if (!"OK".equals(outcome)) {
                            LOGGER.info("ME Control Center could not deliver an alert to the {} of player {}: {}", channel,
                                    event.playerUuid(), outcome);
                        }
                    }));
                }
            }).exceptionally(error -> {
                LOGGER.warn("ME Control Center could not deliver an alert", error);
                return null;
            });
        }
    }

    private Map<String, CompletableFuture<String>> webhooks(AlertSettings settings, AlertEvent event) {
        String text = AlertMessages.text(event, networkNames.apply(event.networkId()), settings.locale());
        Map<String, CompletableFuture<String>> results = new LinkedHashMap<>();
        if (settings.discordWebhookUrl() != null) {
            results.put("discord", post(settings.discordWebhookUrl(), discord(text)));
        }
        if (settings.webhookUrl() != null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("event", event.kind() == AlertEvent.Kind.TRIGGERED ? LiveEvent.ALERT_TRIGGERED : LiveEvent.ALERT_RESOLVED);
            body.put("type", event.type().name());
            body.put("ruleId", event.ruleId().toString());
            body.put("networkId", event.networkId().toString());
            body.put("networkName", networkNames.apply(event.networkId()));
            body.put("resourceId", event.resource() == null ? null : event.resource().resourceId().toString());
            body.put("resourceName", event.resource() == null ? null : event.resource().name(settings.locale()));
            body.put("value", event.value());
            body.put("threshold", event.threshold());
            body.put("orderId", event.orderId() == null ? null : event.orderId().toString());
            body.put("at", event.at().toString());
            body.put("text", text);
            results.put("webhook", post(settings.webhookUrl(), write(body)));
        }
        return results;
    }

    /** Sends a test message to each configured channel; per channel {@code OK} or why it failed. */
    CompletableFuture<Map<String, String>> test(AlertSettings settings) {
        String text = "zh_cn".equals(settings.locale())
                ? "ME Control Center 测试消息：提醒会发送到这里。"
                : "ME Control Center test message: alerts will arrive here.";
        Map<String, CompletableFuture<String>> results = new LinkedHashMap<>();
        if (settings.discordWebhookUrl() != null) {
            results.put("discord", post(settings.discordWebhookUrl(), discord(text)));
        }
        if (settings.webhookUrl() != null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("event", "alert.test");
            body.put("at", clock.instant().toString());
            body.put("text", text);
            results.put("webhook", post(settings.webhookUrl(), write(body)));
        }
        return CompletableFuture.allOf(results.values().toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            Map<String, String> outcome = new LinkedHashMap<>();
            results.forEach((channel, result) -> outcome.put(channel, result.join()));
            return outcome;
        });
    }

    private String discord(String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", "ME Control Center");
        body.put("content", text.length() > DISCORD_MAX_CONTENT ? text.substring(0, DISCORD_MAX_CONTENT) : text);
        // Names come from players (networks, renamed items): never let them ping @everyone or roles.
        body.put("allowed_mentions", Map.of("parse", List.of()));
        return write(body);
    }

    /** Never fails: completes with {@code OK} or a short reason. */
    private CompletableFuture<String> post(String url, String body) {
        URI uri = URI.create(url);
        return CompletableFuture.runAsync(() -> {
                    try {
                        WebhookTargets.checkAddress(uri.getHost(), config.allowPrivateWebhookTargets());
                    } catch (java.net.UnknownHostException e) {
                        throw new CompletionException(e);
                    }
                }, workers)
                .thenCompose(ignored -> http.sendAsync(HttpRequest.newBuilder(uri)
                        .timeout(WEBHOOK_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "ME-Control-Center")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(), HttpResponse.BodyHandlers.discarding()))
                .thenApply(response -> response.statusCode() / 100 == 2 ? "OK" : "HTTP " + response.statusCode())
                .exceptionally(error -> {
                    Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                    return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
                });
    }

    private String write(Map<String, Object> body) {
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
