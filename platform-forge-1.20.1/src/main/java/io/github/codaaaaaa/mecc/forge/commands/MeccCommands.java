package io.github.codaaaaaa.mecc.forge.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.github.codaaaaaa.mecc.core.command.ChatReply;
import io.github.codaaaaaa.mecc.core.command.CommandService;
import io.github.codaaaaaa.mecc.core.command.ServerText;
import io.github.codaaaaaa.mecc.core.users.PlayerProfile;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers {@code /mecc} and renders {@link ChatReply} as Minecraft chat. All logic lives in the
 * version-independent {@link CommandService}; replies are always delivered on the server thread.
 */
public final class MeccCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger(MeccCommands.class);

    private MeccCommands() {
    }

    /**
     * @param commands supplies the current runtime's command service, or {@code null} when ME Control Center is not running
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, Supplier<CommandService> commands) {
        dispatcher.register(Commands.literal("mecc")
                .then(Commands.literal("pair")
                        .executes(context -> pair(context, commands)))
                .then(Commands.literal("devices")
                        .executes(context -> devices(context, commands, null))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(context -> devices(context, commands,
                                        StringArgumentType.getString(context, "player")))))
                .then(Commands.literal("revoke")
                        .then(Commands.argument("device", StringArgumentType.word())
                                .executes(context -> revoke(context, commands,
                                        StringArgumentType.getString(context, "device"))))));
    }

    private static int pair(CommandContext<CommandSourceStack> context, Supplier<CommandService> commands) {
        CommandSourceStack source = context.getSource();
        String locale = locale(source);
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            send(source, ChatReply.error(ServerText.get(locale, "pair.players_only")));
            return 0;
        }
        CommandService service = commands.get();
        if (service == null) {
            send(source, ChatReply.error(ServerText.get(locale, "mecc.not_running")));
            return 0;
        }
        ChatReply reply = service.pair(profile(player), locale);
        send(source, reply);
        return reply.success() ? 1 : 0;
    }

    private static int devices(CommandContext<CommandSourceStack> context, Supplier<CommandService> commands, String target) {
        CommandSourceStack source = context.getSource();
        CommandService service = commands.get();
        if (service == null) {
            send(source, ChatReply.error(ServerText.get(locale(source), "mecc.not_running")));
            return 0;
        }
        deliverLater(source, service.listDevices(actor(source), permissionLevel(source), target, locale(source)));
        return 1;
    }

    private static int revoke(CommandContext<CommandSourceStack> context, Supplier<CommandService> commands, String device) {
        CommandSourceStack source = context.getSource();
        CommandService service = commands.get();
        if (service == null) {
            send(source, ChatReply.error(ServerText.get(locale(source), "mecc.not_running")));
            return 0;
        }
        deliverLater(source, service.revoke(actor(source), permissionLevel(source), device, locale(source)));
        return 1;
    }

    private static void deliverLater(CommandSourceStack source, CompletionStage<ChatReply> reply) {
        reply.whenComplete((result, error) -> {
            ChatReply message = error == null ? result : ChatReply.error(ServerText.get(locale(source), "mecc.internal_error"));
            if (error != null) {
                LOGGER.error("ME Control Center command failed", error);
            }
            // tell() always queues, even while the server is stopping; never touch the source off-thread.
            source.getServer().tell(new TickTask(source.getServer().getTickCount(), () -> send(source, message)));
        });
    }

    private static void send(CommandSourceStack source, ChatReply reply) {
        MutableComponent message = Component.empty();
        boolean firstLine = true;
        for (ChatReply.Line line : reply.lines()) {
            if (!firstLine) {
                message.append("\n");
            }
            firstLine = false;
            for (ChatReply.Span span : line.spans()) {
                message.append(render(span));
            }
        }
        if (reply.success()) {
            // Never broadcast to operators or the console: replies can contain pairing keys.
            source.sendSuccess(() -> message, false);
        } else {
            source.sendFailure(message);
        }
    }

    private static MutableComponent render(ChatReply.Span span) {
        Style style = switch (span.style()) {
            case NORMAL -> Style.EMPTY;
            case MUTED -> Style.EMPTY.withColor(ChatFormatting.GRAY);
            case ACCENT -> Style.EMPTY.withColor(ChatFormatting.AQUA);
            case SUCCESS -> Style.EMPTY.withColor(ChatFormatting.GREEN);
            case WARNING -> Style.EMPTY.withColor(ChatFormatting.GOLD);
            case ERROR -> Style.EMPTY.withColor(ChatFormatting.RED);
            case SECRET -> Style.EMPTY.withColor(ChatFormatting.AQUA).withBold(true);
        };
        if (span.copyText() != null) {
            style = style
                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, span.copyText()))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(span.copyText())));
        }
        if (span.url() != null) {
            style = style.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, span.url())).withUnderlined(true);
        }
        return Component.literal(span.text()).withStyle(style);
    }

    private static PlayerProfile actor(CommandSourceStack source) {
        return source.getEntity() instanceof ServerPlayer player ? profile(player) : null;
    }

    private static PlayerProfile profile(ServerPlayer player) {
        return new PlayerProfile(player.getUUID(), player.getGameProfile().getName());
    }

    private static int permissionLevel(CommandSourceStack source) {
        for (int level = 4; level > 0; level--) {
            if (source.hasPermission(level)) {
                return level;
            }
        }
        return 0;
    }

    private static String locale(CommandSourceStack source) {
        return source.getEntity() instanceof ServerPlayer player ? player.getLanguage() : "en_us";
    }
}
