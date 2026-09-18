package io.github.codaaaaaa.mecc.forge;

import io.github.codaaaaaa.mecc.forge.ae2.Ae2StoragePlatform;
import io.github.codaaaaaa.mecc.forge.ae2.GridRegistry;
import io.github.codaaaaaa.mecc.forge.commands.MeccCommands;
import io.github.codaaaaaa.mecc.runtime.MeccRuntime;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Maps Forge server lifecycle events onto the version-independent {@link MeccRuntime}.
 * Fires for dedicated and integrated servers; these events arrive on the server thread.
 */
final class ForgeLifecycle {
    private volatile MeccRuntime runtime;

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        MeccCommands.register(event.getDispatcher(), () -> {
            MeccRuntime current = runtime;
            return current == null ? null : current.commands();
        });
    }

    @SubscribeEvent
    public void onTagsUpdated(TagsUpdatedEvent event) {
        // Data pack reloads change tags, so cached tag indexes must be rebuilt.
        Ae2StoragePlatform.tagsReloaded();
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        stopRuntime();
        MeccRuntime started = new MeccRuntime(new ForgePlatform(event.getServer()));
        runtime = started;
        started.start();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        stopRuntime();
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        GridRegistry.clear();
    }

    private void stopRuntime() {
        MeccRuntime current = runtime;
        runtime = null;
        if (current != null) {
            current.stop();
        }
    }
}
