package io.github.codaaaaaa.mecc.runtime.alerts;

import io.github.codaaaaaa.mecc.core.alerts.AlertEvent;
import io.github.codaaaaaa.mecc.core.alerts.AlertRule;
import io.github.codaaaaaa.mecc.core.alerts.AlertService;
import io.github.codaaaaaa.mecc.core.alerts.AlertSettings;
import io.github.codaaaaaa.mecc.core.alerts.AlertState;
import io.github.codaaaaaa.mecc.core.alerts.AlertType;
import io.github.codaaaaaa.mecc.core.alerts.AlertViews.EventPage;
import io.github.codaaaaaa.mecc.core.alerts.AlertViews.RuleList;
import io.github.codaaaaaa.mecc.core.alerts.AlertViews.RuleView;
import io.github.codaaaaaa.mecc.core.alerts.AlertViews.SettingsView;
import io.github.codaaaaaa.mecc.core.alerts.AlertViews.TestResult;
import io.github.codaaaaaa.mecc.core.auth.Session;
import io.github.codaaaaaa.mecc.core.config.AlertsConfig;
import io.github.codaaaaaa.mecc.core.crafting.OrderTarget;
import io.github.codaaaaaa.mecc.core.error.ErrorCode;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import io.github.codaaaaaa.mecc.core.permissions.NetworkCapability;
import io.github.codaaaaaa.mecc.core.persistence.DataStore;
import io.github.codaaaaaa.mecc.core.resources.ResourceId;
import io.github.codaaaaaa.mecc.runtime.crafting.ResourceLabels;
import io.github.codaaaaaa.mecc.runtime.networks.NetworkGuard;
import io.github.codaaaaaa.mecc.runtime.resources.DefaultResourceService;
import io.github.codaaaaaa.mecc.runtime.resources.ResourceResolver;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Alert rules, events, and channels (spec section 23). Evaluation is {@link AlertMonitor}'s. */
public final class DefaultAlertService implements AlertService {
    /** Cooldown when a new rule does not name one. */
    static final int DEFAULT_COOLDOWN_MINUTES = 30;
    /** Window of a percentage-change rule that does not name one. */
    static final int DEFAULT_WINDOW_MINUTES = 60;

    private final DataStore store;
    private final NetworkGuard guard;
    private final ResourceResolver resolver;
    private final ResourceLabels labels;
    private final AlertNotifier notifier;
    private final Supplier<String> assetVersion;
    private final AlertsConfig config;
    private final Clock clock;

    public DefaultAlertService(DataStore store, NetworkGuard guard, ResourceResolver resolver, ResourceLabels labels,
                               AlertNotifier notifier, Supplier<String> assetVersion, AlertsConfig config, Clock clock) {
        this.store = store;
        this.guard = guard;
        this.resolver = resolver;
        this.labels = labels;
        this.notifier = notifier;
        this.assetVersion = assetVersion;
        this.config = config;
        this.clock = clock;
    }

    @Override
    public CompletionStage<RuleList> rules(Session session, UUID networkId, String locale) {
        UUID player = session.user().playerUuid();
        return guard.access(session, networkId)
                .thenCompose(access -> {
                    NetworkGuard.require(access, NetworkCapability.VIEW_NETWORK);
                    return store.read(repos -> repos.alerts().rules(player, networkId));
                })
                .thenApply(rules -> new RuleList(rules.stream().map(rule -> view(rule, locale)).toList(),
                        config.maxRulesPerUser(), assetVersion.get()));
    }

    @Override
    public CompletionStage<RuleView> createRule(Session session, UUID networkId, RuleInput input, String locale) {
        ResourceId resource;
        Integer window;
        int cooldown;
        try {
            if (input == null || input.type() == null) {
                throw MeccException.validation("type", "type is required");
            }
            AlertType type = input.type();
            String text = input.resourceId() == null ? "" : input.resourceId().strip();
            if (type.needsResource() && text.isEmpty()) {
                throw MeccException.validation("resourceId", "This alert needs a resource");
            }
            resource = !type.allowsResource() || text.isEmpty() ? null : ResourceId.parse(text)
                    .orElseThrow(() -> MeccException.validation("resourceId", "Not a valid resource ID"));
            checkThreshold(type, input.threshold());
            window = type.needsWindow() ? window(input.windowMinutes(), DEFAULT_WINDOW_MINUTES) : null;
            cooldown = cooldown(input.cooldownMinutes(), type.condition() ? DEFAULT_COOLDOWN_MINUTES : 0);
        } catch (MeccException e) {
            return CompletableFuture.failedFuture(e);
        }
        UUID player = session.user().playerUuid();
        return guard.access(session, networkId)
                .thenCompose(access -> {
                    NetworkGuard.require(access, NetworkCapability.VIEW_NETWORK);
                    return resource == null
                            ? CompletableFuture.<OrderTarget>completedFuture(null)
                            : resolver.resolve(networkId, resource).thenApply(labels::target);
                })
                .thenCompose(target -> {
                    AlertRule rule = new AlertRule(UUID.randomUUID(), player, networkId, input.type(), target,
                            input.type().needsThreshold() ? input.threshold() : null, window, cooldown,
                            input.enabled() == null || input.enabled(), AlertState.OK, null, clock.instant());
                    return store.write(repos -> {
                        if (repos.alerts().countRules(player) >= config.maxRulesPerUser()) {
                            throw new MeccException(ErrorCode.CONFLICT, "You have too many alert rules",
                                    Map.of("limit", config.maxRulesPerUser()));
                        }
                        repos.alerts().insertRule(rule);
                        return rule;
                    });
                })
                .thenApply(rule -> view(rule, locale));
    }

    @Override
    public CompletionStage<RuleView> updateRule(Session session, UUID ruleId, RuleInput input, String locale) {
        UUID player = session.user().playerUuid();
        return store.read(repos -> own(repos.alerts().findRule(ruleId).orElse(null), player))
                .thenCompose(rule -> guard.access(session, rule.networkId()).thenApply(access -> {
                    NetworkGuard.require(access, NetworkCapability.VIEW_NETWORK);
                    return rule;
                }))
                .thenCompose(rule -> {
                    Long threshold = input == null || input.threshold() == null ? rule.threshold() : input.threshold();
                    if (rule.type().needsThreshold()) {
                        checkThreshold(rule.type(), threshold);
                    }
                    int currentWindow = rule.window() == null ? DEFAULT_WINDOW_MINUTES : rule.window();
                    Integer window = rule.type().needsWindow()
                            ? window(input == null ? null : input.windowMinutes(), currentWindow)
                            : null;
                    int cooldown = cooldown(input == null ? null : input.cooldownMinutes(), rule.cooldown());
                    boolean enabled = input == null || input.enabled() == null ? rule.enabled() : input.enabled();
                    return store.write(repos -> {
                        AlertRule current = own(repos.alerts().findRule(ruleId).orElse(null), player);
                        // A disabled rule forgets whether it was firing; enabling it again starts afresh.
                        AlertRule updated = new AlertRule(current.id(), player, current.networkId(), current.type(),
                                current.resource(), rule.type().needsThreshold() ? threshold : null, window, cooldown, enabled,
                                enabled ? current.state() : AlertState.OK, current.notifiedAt(), current.createdAt());
                        repos.alerts().updateRule(updated);
                        return updated;
                    });
                })
                .thenApply(rule -> view(rule, locale));
    }

    @Override
    public CompletionStage<Void> deleteRule(Session session, UUID ruleId) {
        UUID player = session.user().playerUuid();
        return store.write(repos -> {
            own(repos.alerts().findRule(ruleId).orElse(null), player);
            repos.alerts().deleteRule(ruleId);
            return null;
        });
    }

    @Override
    public CompletionStage<EventPage> events(Session session, UUID networkId, Long before, int limit, String locale) {
        UUID player = session.user().playerUuid();
        CompletableFuture<Void> allowed = networkId == null
                ? CompletableFuture.completedFuture(null)
                : guard.access(session, networkId).thenAccept(access -> NetworkGuard.require(access, NetworkCapability.VIEW_NETWORK));
        return allowed
                .thenCompose(ignored -> store.read(repos -> repos.alerts().events(player, networkId, before, limit + 1)))
                .thenApply(events -> {
                    List<AlertEvent> page = events.size() > limit ? events.subList(0, limit) : events;
                    Long next = events.size() > limit ? page.get(page.size() - 1).id() : null;
                    return new EventPage(page.stream().map(event -> notifier.view(event, locale(locale))).toList(), next,
                            assetVersion.get());
                });
    }

    @Override
    public CompletionStage<SettingsView> settings(Session session) {
        UUID player = session.user().playerUuid();
        return store.read(repos -> repos.alerts().settings(player))
                .thenApply(settings -> new SettingsView(settings.map(AlertSettings::discordWebhookUrl).orElse(null),
                        settings.map(AlertSettings::webhookUrl).orElse(null), config.webhooksEnabled()));
    }

    @Override
    public CompletionStage<SettingsView> updateSettings(Session session, SettingsInput input) {
        AlertSettings settings;
        try {
            String discord = WebhookTargets.discord(input == null ? null : input.discordWebhookUrl());
            String webhook = WebhookTargets.generic(input == null ? null : input.webhookUrl());
            if (!config.webhooksEnabled() && (discord != null || webhook != null)) {
                throw MeccException.validation("webhookUrl", "Webhooks are turned off on this server");
            }
            settings = new AlertSettings(session.user().playerUuid(), discord, webhook,
                    locale(input == null ? null : input.locale()));
        } catch (MeccException e) {
            return CompletableFuture.failedFuture(e);
        }
        return store.write(repos -> {
            repos.alerts().putSettings(settings);
            return new SettingsView(settings.discordWebhookUrl(), settings.webhookUrl(), config.webhooksEnabled());
        });
    }

    @Override
    public CompletionStage<TestResult> test(Session session) {
        UUID player = session.user().playerUuid();
        return store.read(repos -> repos.alerts().settings(player)).thenCompose(settings -> {
            if (!config.webhooksEnabled() || settings.isEmpty()
                    || (settings.get().discordWebhookUrl() == null && settings.get().webhookUrl() == null)) {
                return CompletableFuture.completedFuture(new TestResult(Map.of()));
            }
            return notifier.test(settings.get()).thenApply(TestResult::new);
        });
    }

    private static AlertRule own(AlertRule rule, UUID player) {
        if (rule == null || !rule.playerUuid().equals(player)) {
            throw new MeccException(ErrorCode.NOT_FOUND, "Alert rule not found");
        }
        return rule;
    }

    private static void checkThreshold(AlertType type, Long threshold) {
        if (!type.needsThreshold()) {
            return;
        }
        if (threshold == null) {
            throw MeccException.validation("threshold", "threshold is required");
        }
        long max = switch (type) {
            case ENERGY_LOW -> 99;
            case RESOURCE_DROP -> 100;
            case RESOURCE_RISE -> 100_000;
            case CRAFT_STALLED, MACHINE_STUCK -> AlertRule.MAX_WINDOW_MINUTES;
            default -> Long.MAX_VALUE;
        };
        long min = type.threshold() == AlertType.Threshold.AMOUNT ? 0 : 1;
        if (threshold < min || threshold > max) {
            throw MeccException.validation("threshold", "threshold must be between " + min + " and " + max);
        }
    }

    private static int window(Integer requested, int fallback) {
        int window = requested == null ? fallback : requested;
        if (window < 1 || window > AlertRule.MAX_WINDOW_MINUTES) {
            throw MeccException.validation("windowMinutes",
                    "windowMinutes must be between 1 and " + AlertRule.MAX_WINDOW_MINUTES);
        }
        return window;
    }

    private static int cooldown(Integer requested, int fallback) {
        int cooldown = requested == null ? fallback : requested;
        if (cooldown < 0 || cooldown > AlertRule.MAX_COOLDOWN_MINUTES) {
            throw MeccException.validation("cooldownMinutes",
                    "cooldownMinutes must be between 0 and " + AlertRule.MAX_COOLDOWN_MINUTES);
        }
        return cooldown;
    }

    private static String locale(String locale) {
        return locale != null && DefaultResourceService.LOCALES.contains(locale) ? locale : "en_us";
    }

    private RuleView view(AlertRule rule, String locale) {
        return new RuleView(rule.id(), rule.networkId(), rule.type(),
                rule.resource() == null ? null : labels.label(rule.resource(), locale(locale)), rule.threshold(),
                rule.window(), rule.cooldown(), rule.enabled(), rule.state() != AlertState.OK, rule.notifiedAt(),
                rule.createdAt());
    }
}
