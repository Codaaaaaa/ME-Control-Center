package io.github.codaaaaaa.mecc.forge.ae2;

import appeng.api.ids.AEConstants;
import io.github.codaaaaaa.mecc.platform.Ae2Platform;
import java.util.Optional;
import net.minecraftforge.fml.ModList;

/**
 * AE2 15.x integration boundary for Minecraft 1.20.1. All AE2 API usage for this platform lives in
 * this package; later milestones add grid discovery, storage, crafting, and pattern adapters here.
 */
public final class Ae2Integration implements Ae2Platform {
    /** Keep in sync with {@code ae2_version} in gradle.properties. */
    public static final String TESTED_VERSION = "15.4.10";

    private final Optional<String> installedVersion = ModList.get().getModContainerById(AEConstants.MOD_ID)
            .map(container -> container.getModInfo().getVersion().toString());

    @Override
    public Optional<String> installedVersion() {
        return installedVersion;
    }

    @Override
    public String testedVersion() {
        return TESTED_VERSION;
    }
}
