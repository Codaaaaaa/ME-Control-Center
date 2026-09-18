package io.github.codaaaaaa.mecc.core.users;

import java.util.Objects;
import java.util.UUID;

/** A Minecraft player identity as reported by the platform. */
public record PlayerProfile(UUID uuid, String name) {
    public PlayerProfile {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
    }
}
