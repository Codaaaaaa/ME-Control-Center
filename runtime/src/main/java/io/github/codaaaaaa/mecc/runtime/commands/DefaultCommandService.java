package io.github.codaaaaaa.mecc.runtime.commands;

import io.github.codaaaaaa.mecc.core.audit.AuditAction;
import io.github.codaaaaaa.mecc.core.audit.AuditEvent;
import io.github.codaaaaaa.mecc.core.audit.AuditEvent.AuditResult;
import io.github.codaaaaaa.mecc.core.auth.Device;
import io.github.codaaaaaa.mecc.core.auth.PairingService;
import io.github.codaaaaaa.mecc.core.auth.PairingService.PairingKey;
import io.github.codaaaaaa.mecc.core.command.ChatReply;
import io.github.codaaaaaa.mecc.core.command.ChatReply.Style;
import io.github.codaaaaaa.mecc.core.command.CommandService;
import io.github.codaaaaaa.mecc.core.command.ServerText;
import io.github.codaaaaaa.mecc.core.error.ErrorCode;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import io.github.codaaaaaa.mecc.core.persistence.DataStore;
import io.github.codaaaaaa.mecc.core.users.PlayerProfile;
import io.github.codaaaaaa.mecc.core.users.WebUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Implements {@code /mecc pair}, {@code /mecc devices}, and {@code /mecc revoke}. */
public final class DefaultCommandService implements CommandService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultCommandService.class);

    private final PairingService pairing;
    private final DataStore store;
    private final Duration pairingKeyTtl;
    private final int adminOpLevel;
    private final String pairingUrl;
    private final Clock clock;

    /**
     * @param pairingUrl URL shown with a pairing key, or {@code null} when ME Control Center cannot know its public address
     */
    public DefaultCommandService(PairingService pairing, DataStore store, Duration pairingKeyTtl, int adminOpLevel,
                                 String pairingUrl, Clock clock) {
        this.pairing = pairing;
        this.store = store;
        this.pairingKeyTtl = pairingKeyTtl;
        this.adminOpLevel = adminOpLevel;
        this.pairingUrl = pairingUrl;
        this.clock = clock;
    }

    @Override
    public ChatReply pair(PlayerProfile player, String locale) {
        PairingKey key;
        try {
            key = pairing.issue(player);
        } catch (MeccException e) {
            if (e.code() == ErrorCode.RATE_LIMITED) {
                return ChatReply.error(ServerText.get(locale, "pair.rate_limited"));
            }
            throw e;
        }
        ChatReply.Builder reply = ChatReply.ok()
                .line()
                .text(ServerText.get(locale, "pair.header"), Style.NORMAL)
                .copyable(key.key(), Style.SECRET, key.key())
                .text(ServerText.get(locale, "pair.copy_hint"), Style.MUTED)
                .line()
                .text(ServerText.get(locale, "pair.instructions", Math.max(1, pairingKeyTtl.toMinutes())), Style.MUTED)
                .line();
        if (pairingUrl != null) {
            reply.text(ServerText.get(locale, "pair.open"), Style.NORMAL).link(pairingUrl, pairingUrl);
        } else {
            reply.text(ServerText.get(locale, "pair.no_url"), Style.MUTED);
        }
        LOGGER.info("ME Control Center pairing key issued for {}", player.name());
        return reply.build();
    }

    @Override
    public CompletionStage<ChatReply> listDevices(PlayerProfile actor, int permissionLevel, String targetPlayer, String locale) {
        boolean admin = actor == null || permissionLevel >= adminOpLevel;
        boolean own = targetPlayer == null || (actor != null && targetPlayer.equalsIgnoreCase(actor.name()));
        if (actor == null && targetPlayer == null) {
            return CompletableFuture.completedFuture(ChatReply.error(ServerText.get(locale, "pair.players_only")));
        }
        if (!own && !admin) {
            return CompletableFuture.completedFuture(ChatReply.error(ServerText.get(locale, "devices.admin_only")));
        }
        return guard(locale, store.read(repos -> {
            Optional<WebUser> user = own && actor != null
                    ? repos.users().find(actor.uuid())
                    : repos.users().findByName(targetPlayer);
            if (user.isEmpty()) {
                if (own) {
                    return ChatReply.ok().line().text(ServerText.get(locale, "devices.none"), Style.MUTED).build();
                }
                return ChatReply.error(ServerText.get(locale, "devices.unknown_player", targetPlayer));
            }
            List<Device> devices = repos.devices().listActive(user.get().playerUuid());
            if (devices.isEmpty()) {
                return ChatReply.ok().line().text(ServerText.get(locale, "devices.none"), Style.MUTED).build();
            }
            Instant now = clock.instant();
            ChatReply.Builder reply = ChatReply.ok().line().text(own
                    ? ServerText.get(locale, "devices.header_own", devices.size())
                    : ServerText.get(locale, "devices.header_other", user.get().playerName(), devices.size()), Style.NORMAL);
            for (Device device : devices) {
                reply.line()
                        .text("  ", Style.NORMAL)
                        .copyable(device.id(), Style.ACCENT, device.id())
                        .text("  " + device.name(), Style.NORMAL)
                        .text(ServerText.get(locale, "devices.entry",
                                ServerText.ago(locale, Duration.between(device.lastUsedAt(), now))), Style.MUTED);
            }
            return reply.line().text(ServerText.get(locale, "devices.hint"), Style.MUTED).build();
        }));
    }

    @Override
    public CompletionStage<ChatReply> revoke(PlayerProfile actor, int permissionLevel, String deviceIdOrAll, String locale) {
        boolean admin = actor == null || permissionLevel >= adminOpLevel;
        String argument = deviceIdOrAll.strip();
        if (argument.equalsIgnoreCase("all")) {
            if (actor == null) {
                return CompletableFuture.completedFuture(ChatReply.error(ServerText.get(locale, "revoke.console_all")));
            }
            return guard(locale, store.write(repos -> {
                Instant now = clock.instant();
                int count = repos.devices().revokeAll(actor.uuid(), null, now);
                if (count > 0) {
                    repos.audit().append(new AuditEvent(now, actor.uuid(), null, null, AuditAction.DEVICE_REVOKE, "all",
                            AuditResult.SUCCESS, false, Map.of("count", Integer.toString(count), "source", "command")));
                }
                return ChatReply.ok().line().text(ServerText.get(locale, "revoke.all_done", count), Style.SUCCESS).build();
            }));
        }
        return guard(locale, store.write(repos -> {
            Optional<Device> device = repos.devices().find(argument)
                    .filter(Device::active)
                    .filter(found -> admin || found.playerUuid().equals(actor.uuid()));
            if (device.isEmpty()) {
                return ChatReply.error(ServerText.get(locale, "revoke.not_found", argument));
            }
            Instant now = clock.instant();
            repos.devices().revoke(device.get().id(), now);
            boolean foreign = actor == null || !device.get().playerUuid().equals(actor.uuid());
            repos.audit().append(new AuditEvent(now, actor == null ? null : actor.uuid(), null, null,
                    AuditAction.DEVICE_REVOKE, device.get().id(), AuditResult.SUCCESS, foreign,
                    Map.of("source", actor == null ? "console" : "command", "owner", device.get().playerUuid().toString())));
            return ChatReply.ok().line().text(ServerText.get(locale, "revoke.done", device.get().id()), Style.SUCCESS).build();
        }));
    }

    private static CompletionStage<ChatReply> guard(String locale, CompletableFuture<ChatReply> work) {
        return work.exceptionally(error -> {
            LOGGER.error("ME Control Center command failed", error);
            return ChatReply.error(ServerText.get(locale, "mecc.internal_error"));
        });
    }
}
