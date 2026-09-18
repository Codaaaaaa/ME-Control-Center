package io.github.codaaaaaa.mecc.forge;

import io.github.codaaaaaa.mecc.core.status.ServerSnapshot;
import io.github.codaaaaaa.mecc.platform.ServerInfoPlatform;
import io.github.codaaaaaa.mecc.platform.ServerThreadOnly;
import net.minecraft.server.MinecraftServer;

final class ForgeServerInfo implements ServerInfoPlatform {
    private final MinecraftServer server;

    ForgeServerInfo(MinecraftServer server) {
        this.server = server;
    }

    @Override
    @ServerThreadOnly
    public ServerSnapshot snapshot() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("ServerInfoPlatform.snapshot() must run on the server thread");
        }
        return new ServerSnapshot(
                server.isDedicatedServer(),
                server.getPlayerCount(),
                server.getMaxPlayers(),
                server.getAverageTickTime(),
                server.getMotd());
    }
}
