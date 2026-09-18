package io.github.codaaaaaa.mecc.exporter;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

/**
 * Client-side entry points: the {@code /mecc_export [languages]} command, usable in single player and on
 * any server, and an optional automatic export once the game has loaded, for scripted modpack builds
 * ({@code -Dmecc.exporter.autorun=en_us,zh_cn}, plus {@code -Dmecc.exporter.exitAfter=true} to quit).
 */
final class ExporterCommands {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String AUTORUN = System.getProperty("mecc.exporter.autorun", "");
    private static final boolean EXIT_AFTER = Boolean.getBoolean("mecc.exporter.exitAfter");
    /** Always exported: the server's fallback language and the web UI's default. */
    private static final List<String> DEFAULT_LOCALES = List.of("en_us", "zh_cn");

    private ExportJob job;
    private volatile boolean resourcesLoaded;
    private boolean autorunStarted;

    @SubscribeEvent
    public void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mecc_export")
                .executes(context -> start("") ? 1 : 0)
                .then(Commands.argument("languages", StringArgumentType.greedyString())
                        .executes(context -> start(StringArgumentType.getString(context, "languages")) ? 1 : 0)));
    }

    /**
     * Mod bus. Registered last, so it runs after models and languages have loaded: from then on everything
     * an export needs is there, whether or not the loading screen has finished, which it never does while
     * the window is minimized.
     */
    void registerReloadListener(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) resources -> resourcesLoaded = true);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!AUTORUN.isEmpty() && !autorunStarted && resourcesLoaded) {
            autorunStarted = true;
            start("true".equalsIgnoreCase(AUTORUN) ? "" : AUTORUN);
        }
        if (job == null) {
            return;
        }
        try {
            job.tick();
        } catch (RuntimeException | LinkageError e) {
            // Whatever goes wrong, the export is what stops, never the game.
            LOGGER.error("ME Control Center export failed", e);
            say(Component.translatable("mecc_exporter.failed", String.valueOf(e)));
            job = null;
            return;
        }
        if (job.isDone()) {
            job = null;
            if (EXIT_AFTER && !AUTORUN.isEmpty()) {
                Minecraft.getInstance().stop();
            }
        }
    }

    private boolean start(String languages) {
        if (job != null) {
            say(Component.translatable("mecc_exporter.busy"));
            return false;
        }
        List<String> locales = locales(languages);
        if (locales.isEmpty()) {
            return false;
        }
        try {
            Path directory = Minecraft.getInstance().gameDirectory.toPath().resolve("mecc-exports").normalize();
            job = new ExportJob(locales, directory, ExporterCommands::say);
            return true;
        } catch (Exception | LinkageError e) {
            // LinkageError too: a mod jar replaced while the game runs cannot load its classes any more, and
            // that must not crash the game.
            LOGGER.error("Could not start the ME Control Center export", e);
            say(Component.translatable("mecc_exporter.failed", String.valueOf(e)));
            return false;
        }
    }

    /**
     * English and Chinese, the client's current language, and whatever was asked for: codes separated by
     * spaces or commas, or {@code all} for every language the client knows.
     */
    private static List<String> locales(String requested) {
        Set<String> known = Minecraft.getInstance().getLanguageManager().getLanguages().keySet();
        Set<String> locales = new LinkedHashSet<>(DEFAULT_LOCALES);
        locales.add(Minecraft.getInstance().getLanguageManager().getSelected());
        for (String code : requested.toLowerCase(Locale.ROOT).split("[\\s,]+")) {
            if (code.isEmpty()) {
                continue;
            }
            if (code.equals("all")) {
                locales.addAll(known);
            } else if (known.contains(code)) {
                locales.add(code);
            } else {
                say(Component.translatable("mecc_exporter.unknown_locale", code));
                return List.of();
            }
        }
        return new ArrayList<>(locales);
    }

    static Component fileLink(Path file) {
        String absolute = file.toAbsolutePath().toString();
        return Component.literal(file.getFileName().toString()).withStyle(style -> style.withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, absolute)));
    }

    /** Chat when there is one to read, and the log always, since exports also run from the title screen. */
    private static void say(Component message) {
        LOGGER.info(message.getString());
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(message, false);
        }
    }
}
