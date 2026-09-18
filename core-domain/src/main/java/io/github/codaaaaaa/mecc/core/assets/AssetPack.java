package io.github.codaaaaaa.mecc.core.assets;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A source of static game assets: a directory (possibly inside a jar or zip file system) that contains
 * {@code assets/<namespace>/...}. Only read as data, never executed (spec section 36).
 *
 * @param id      human-readable identifier for logs, e.g. the mod file name
 * @param root    directory containing {@code assets/}
 * @param version changes when the pack's content may have changed (e.g. file size and modification time)
 */
public record AssetPack(String id, Path root, String version) {
    public AssetPack {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(version, "version");
    }
}
