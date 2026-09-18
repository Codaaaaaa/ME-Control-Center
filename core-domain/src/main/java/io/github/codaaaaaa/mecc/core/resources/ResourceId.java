package io.github.codaaaaaa.mecc.core.resources;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Version-independent identity of a storable resource (spec section 8).
 *
 * <p>Text form: {@code type:namespace:path} or {@code type:namespace:path:variant}, e.g.
 * {@code item:minecraft:iron_ingot}, {@code fluid:minecraft:water},
 * {@code item:minecraft:enchanted_book:3f9a0c1d2e4b}.
 *
 * @param type      resource type, e.g. {@code item}, {@code fluid}, {@code appmek_chemical}
 * @param namespace registry namespace
 * @param path      registry path (may contain {@code /})
 * @param variant   stable hash of distinguishing data such as NBT, or {@code null}
 */
public record ResourceId(String type, String namespace, String path, String variant) implements Comparable<ResourceId> {
    private static final Pattern TYPE = Pattern.compile("^[a-z0-9_]{1,64}$");
    private static final Pattern NAMESPACE = Pattern.compile("^[a-z0-9_.\\-]{1,64}$");
    private static final Pattern PATH = Pattern.compile("^[a-z0-9_.\\-/]{1,256}$");
    private static final Pattern VARIANT = Pattern.compile("^[0-9a-f]{1,64}$");

    public ResourceId {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (!TYPE.matcher(type).matches() || !NAMESPACE.matcher(namespace).matches() || !PATH.matcher(path).matches()
                || (variant != null && !VARIANT.matcher(variant).matches())) {
            throw new IllegalArgumentException("Invalid resource id: " + type + ":" + namespace + ":" + path + ":" + variant);
        }
    }

    public static ResourceId of(String type, String namespace, String path) {
        return new ResourceId(type, namespace, path, null);
    }

    public static Optional<ResourceId> parse(String text) {
        if (text == null || text.length() > 512) {
            return Optional.empty();
        }
        String[] parts = text.split(":", -1);
        if (parts.length != 3 && parts.length != 4) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ResourceId(parts[0], parts[1], parts[2], parts.length == 4 ? parts[3] : null));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Registry identifier without type or variant, e.g. {@code minecraft:iron_ingot}. */
    public String registryId() {
        return namespace + ":" + path;
    }

    /** The same resource without its variant. */
    public ResourceId base() {
        return variant == null ? this : new ResourceId(type, namespace, path, null);
    }

    @Override
    public String toString() {
        String base = type + ":" + namespace + ":" + path;
        return variant == null ? base : base + ":" + variant;
    }

    @Override
    public int compareTo(ResourceId other) {
        return toString().compareTo(other.toString());
    }
}
