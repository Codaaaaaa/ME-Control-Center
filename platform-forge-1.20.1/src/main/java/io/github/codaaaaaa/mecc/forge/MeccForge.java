package io.github.codaaaaaa.mecc.forge;

import io.github.codaaaaaa.mecc.forge.ae2.GridRegistry;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkConstants;

/** Forge entry point. Keeps Forge-specific bootstrap to a minimum and delegates to the ME Control Center runtime. */
@Mod(MeccForge.MOD_ID)
public final class MeccForge {
    public static final String MOD_ID = "mecc";

    public MeccForge() {
        // Server-side only: clients without ME Control Center can join, and clients that happen to have it are fine too.
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> NetworkConstants.IGNORESERVERONLY, (remote, fromServer) -> true));
        // AE2 constructs grid services when grids are created, so the tracker must be registered before
        // any world loads. mods.toml orders ME Control Center after AE2.
        GridRegistry.registerGridService();
        MinecraftForge.EVENT_BUS.register(new ForgeLifecycle());
    }
}
