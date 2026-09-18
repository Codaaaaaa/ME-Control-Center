package io.github.codaaaaaa.mecc.core.config;

import java.util.List;

/**
 * Icon and name asset settings ({@code [assets]} section).
 *
 * @param downloadVanillaAssets download the vanilla Minecraft client jar and language files from Mojang
 *                              for vanilla icons and names. Dedicated servers do not include them.
 *                              Off by default: enabling it means accepting Mojang's terms for these files.
 */
public record AssetsConfig(boolean downloadVanillaAssets) {

    public static AssetsConfig defaults() {
        return new AssetsConfig(false);
    }

    public List<String> validate() {
        return List.of();
    }
}
