package io.github.codaaaaaa.mecc.platform;

import io.github.codaaaaaa.mecc.core.status.ServerSnapshot;

/** Reads basic Minecraft server state. */
public interface ServerInfoPlatform {

    /** Copies current server state into an immutable snapshot. Keep it cheap: it runs inside a tick. */
    @ServerThreadOnly
    ServerSnapshot snapshot();
}
