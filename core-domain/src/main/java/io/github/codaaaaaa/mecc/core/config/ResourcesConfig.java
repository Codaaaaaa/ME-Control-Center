package io.github.codaaaaaa.mecc.core.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Resource terminal settings ({@code [resources]} section).
 *
 * @param snapshotMaxAgeSeconds a storage snapshot younger than this is reused instead of reading the
 *                              network again; bounds server-thread work no matter how many browsers poll
 */
public record ResourcesConfig(int snapshotMaxAgeSeconds) {
    public static final int DEFAULT_SNAPSHOT_MAX_AGE_SECONDS = 5;

    public static ResourcesConfig defaults() {
        return new ResourcesConfig(DEFAULT_SNAPSHOT_MAX_AGE_SECONDS);
    }

    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (snapshotMaxAgeSeconds < 1 || snapshotMaxAgeSeconds > 60) {
            problems.add("resources.snapshot_max_age_seconds must be between 1 and 60, got " + snapshotMaxAgeSeconds);
        }
        return problems;
    }
}
