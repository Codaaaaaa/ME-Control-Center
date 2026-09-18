package io.github.codaaaaaa.mecc.exporter;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkConstants;

/** Forge entry point of the optional ME Control Center Client Exporter (spec section 19). */
@Mod(MeccExporter.MOD_ID)
public final class MeccExporter {
    public static final String MOD_ID = "mecc_exporter";

    public MeccExporter() {
        // Never part of the handshake: joining any server works with or without it.
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> NetworkConstants.IGNORESERVERONLY, (remote, fromServer) -> true));
        // Installed on a dedicated server by mistake, it simply does nothing.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ExporterCommands commands = new ExporterCommands();
            MinecraftForge.EVENT_BUS.register(commands);
            FMLJavaModLoadingContext.get().getModEventBus().addListener(commands::registerReloadListener);
        }
    }

    static String version() {
        return ModList.get().getModContainerById(MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown");
    }
}
