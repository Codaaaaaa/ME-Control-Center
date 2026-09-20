package io.github.codaaaaaa.mecc.core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Root ME Control Center configuration. Later milestones add sections (analytics, limits, ...). */
public record MeccConfig(
        WebConfig web,
        SecurityConfig security,
        NetworksConfig networks,
        ResourcesConfig resources,
        AssetsConfig assets,
        CraftingConfig crafting,
        PatternsConfig patterns,
        AnalyticsConfig analytics,
        AlertsConfig alerts,
        AutomationConfig automation) {

    public MeccConfig {
        Objects.requireNonNull(web, "web");
        Objects.requireNonNull(security, "security");
        Objects.requireNonNull(networks, "networks");
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(assets, "assets");
        Objects.requireNonNull(crafting, "crafting");
        Objects.requireNonNull(patterns, "patterns");
        Objects.requireNonNull(analytics, "analytics");
        Objects.requireNonNull(alerts, "alerts");
        Objects.requireNonNull(automation, "automation");
    }

    public static MeccConfig defaults() {
        return new MeccConfig(WebConfig.defaults(), SecurityConfig.defaults(), NetworksConfig.defaults(),
                ResourcesConfig.defaults(), AssetsConfig.defaults(), CraftingConfig.defaults(), PatternsConfig.defaults(),
                AnalyticsConfig.defaults(), AlertsConfig.defaults(), AutomationConfig.defaults());
    }

    /** Returns human-readable problems; empty when valid. */
    public List<String> validate() {
        List<String> problems = new ArrayList<>(web.validate());
        problems.addAll(security.validate());
        problems.addAll(networks.validate());
        problems.addAll(resources.validate());
        problems.addAll(assets.validate());
        problems.addAll(crafting.validate());
        problems.addAll(patterns.validate());
        problems.addAll(analytics.validate());
        problems.addAll(alerts.validate());
        problems.addAll(automation.validate());
        return problems;
    }
}
