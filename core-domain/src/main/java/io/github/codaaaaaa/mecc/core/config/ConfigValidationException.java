package io.github.codaaaaaa.mecc.core.config;

import java.util.List;

/** Thrown when {@code mecc.toml} cannot be parsed or contains invalid values. */
public final class ConfigValidationException extends Exception {
    private static final long serialVersionUID = 1L;

    private final List<String> problems;

    public ConfigValidationException(String source, List<String> problems) {
        super("Invalid ME Control Center configuration in " + source + ": " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
