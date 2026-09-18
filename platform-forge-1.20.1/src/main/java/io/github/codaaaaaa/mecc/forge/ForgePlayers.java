package io.github.codaaaaaa.mecc.forge;

import com.mojang.authlib.GameProfile;
import io.github.codaaaaaa.mecc.core.users.PlayerProfile;
import io.github.codaaaaaa.mecc.platform.PlayerPlatform;
import io.github.codaaaaaa.mecc.platform.ServerThreadOnly;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

final class ForgePlayers implements PlayerPlatform {
    private final MinecraftServer server;

    ForgePlayers(MinecraftServer server) {
        this.server = server;
    }

    @Override
    @ServerThreadOnly
    public int permissionLevel(PlayerProfile player) {
        requireServerThread();
        // Reads the local operator list only; never contacts Mojang.
        return server.getProfilePermissions(new GameProfile(player.uuid(), player.name()));
    }

    @Override
    @ServerThreadOnly
    public Optional<PlayerProfile> findOnlinePlayer(String name) {
        requireServerThread();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getGameProfile().getName().equalsIgnoreCase(name)) {
                return Optional.of(profileOf(player));
            }
        }
        return Optional.empty();
    }

    static PlayerProfile profileOf(ServerPlayer player) {
        return new PlayerProfile(player.getUUID(), player.getGameProfile().getName());
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("PlayerPlatform methods must run on the server thread");
        }
    }
}
