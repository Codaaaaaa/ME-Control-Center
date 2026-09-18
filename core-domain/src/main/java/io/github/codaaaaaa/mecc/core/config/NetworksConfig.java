package io.github.codaaaaaa.mecc.core.config;

import java.util.ArrayList;
import java.util.List;

/**
 * ME network discovery settings ({@code [networks]} section).
 *
 * @param discoveryIntervalSeconds how often loaded networks are re-discovered and their status refreshed
 */
public record NetworksConfig(int discoveryIntervalSeconds) {
    public static final int DEFAULT_DISCOVERY_INTERVAL_SECONDS = 10;

    public static NetworksConfig defaults() {
        return new NetworksConfig(DEFAULT_DISCOVERY_INTERVAL_SECONDS);
    }

    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (discoveryIntervalSeconds < 2 || discoveryIntervalSeconds > 300) {
            problems.add("networks.discovery_interval_seconds must be between 2 and 300, got " + discoveryIntervalSeconds);
        }
        return problems;
    }
}
