package io.github.codaaaaaa.mecc.core.command;

import io.github.codaaaaaa.mecc.core.users.PlayerProfile;
import java.util.concurrent.CompletionStage;

/**
 * Logic behind the {@code /mecc} commands. Platform adapters register the command syntax and render
 * replies; everything else lives here.
 *
 * <p>{@link #pair} is synchronous and cheap because it runs on the server thread. The others touch the
 * database and complete asynchronously; adapters must deliver their replies back on the server thread.
 */
public interface CommandService {

    /** {@code /mecc pair}. */
    ChatReply pair(PlayerProfile player, String locale);

    /**
     * {@code /mecc devices [player]}.
     *
     * @param permissionLevel the actor's operator level; listing another player's devices needs admin level
     * @param targetPlayer    player name, or {@code null} for the actor's own devices
     */
    CompletionStage<ChatReply> listDevices(PlayerProfile actor, int permissionLevel, String targetPlayer, String locale);

    /**
     * {@code /mecc revoke <device|all>}. Players may revoke their own devices; admins any device.
     *
     * @param actor {@code null} when run from the server console (treated as admin)
     */
    CompletionStage<ChatReply> revoke(PlayerProfile actor, int permissionLevel, String deviceIdOrAll, String locale);
}
