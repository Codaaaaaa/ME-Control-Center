package io.github.codaaaaaa.mecc.core.patterns;

import io.github.codaaaaaa.mecc.core.resources.ResourceId;
import java.util.Objects;

/**
 * An amount of a resource in a pattern, in the resource's raw unit (e.g. millibuckets for fluids).
 *
 * @param amount at least 1
 */
public record PatternStack(ResourceId resource, long amount) {
    public PatternStack {
        Objects.requireNonNull(resource, "resource");
        if (amount < 1) {
            throw new IllegalArgumentException("amount must be at least 1");
        }
    }
}
