package io.github.codaaaaaa.mecc.forge.ae2;

import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import com.mojang.authlib.GameProfile;
import io.github.codaaaaaa.mecc.core.error.ErrorCode;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import io.github.codaaaaaa.mecc.core.users.PlayerProfile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.util.FakePlayerFactory;

/** Helpers shared by the AE2 adapters. */
final class Ae2Support {
    private Ae2Support() {
    }

    /** The grid behind a runtime key, or {@code NETWORK_OFFLINE}. Server thread only. */
    static IGrid grid(String gridKey) {
        return GridRegistry.find(gridKey).orElseThrow(() ->
                new MeccException(ErrorCode.NETWORK_OFFLINE, "The ME network is not loaded right now"));
    }

    /**
     * Acts as the requesting player, like a terminal would: AE2's security, CPU selection modes, and
     * notifications then work as in game. Offline players are represented by a fake player with their profile.
     */
    static IActionSource actionSource(MinecraftServer server, PlayerProfile requester, Level level, IGrid grid) {
        Player player = server.getPlayerList().getPlayer(requester.uuid());
        if (player == null && level instanceof ServerLevel serverLevel) {
            player = FakePlayerFactory.get(serverLevel, new GameProfile(requester.uuid(), requester.name()));
        }
        IActionHost host = grid::getPivot;
        return player == null ? IActionSource.ofMachine(host) : IActionSource.ofPlayer(player, host);
    }

    /** Short, URL-safe, stable identifier derived from a location-like key. */
    static String shortHash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static void requireServerThread(MinecraftServer server, String what) {
        if (!server.isSameThread()) {
            throw new IllegalStateException(what + " must run on the server thread");
        }
    }
}
