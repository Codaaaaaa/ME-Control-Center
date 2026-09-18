package io.github.codaaaaaa.mecc.platform;

import io.github.codaaaaaa.mecc.core.assets.AssetPack;
import java.util.List;
import java.util.Map;

/** Static asset sources of the installed game and mods (spec section 20, Tier 1). All methods are thread-safe. */
public interface AssetPlatform {

    /**
     * Asset packs from installed mod files and platform-specific locations, lowest priority first.
     * Resources in later packs override earlier ones.
     */
    List<AssetPack> assetPacks();

    /** Mod id to display name. */
    Map<String, String> modNames();

    /** Mod id to version, to tell whether an imported content pack comes from the same modpack. */
    default Map<String, String> modVersions() {
        return Map.of();
    }
}
