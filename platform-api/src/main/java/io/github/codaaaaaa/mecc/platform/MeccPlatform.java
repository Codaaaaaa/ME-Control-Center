package io.github.codaaaaaa.mecc.platform;

import io.github.codaaaaaa.mecc.core.status.PlatformInfo;
import io.github.codaaaaaa.mecc.platform.thread.ServerThreadExecutor;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Entry point a platform adapter hands to the ME Control Center runtime.
 *
 * <p>Methods here are thread-safe unless a returned capability says otherwise
 * (see {@link ServerThreadOnly}). Later milestones add capability interfaces such as
 * storage, crafting, and pattern platforms.
 */
public interface MeccPlatform {

    /** Static adapter description. Thread-safe. */
    PlatformInfo info();

    /** Installed ME Control Center mod version. Thread-safe. */
    String meccVersion();

    /** Directory for ME Control Center configuration files, e.g. {@code <server>/config/mecc}. Thread-safe. */
    Path configDirectory();

    /**
     * Directory for ME Control Center's persistent data. World-specific, because network anchors refer to world
     * positions, e.g. {@code <world>/mecc}. Thread-safe.
     */
    Path dataDirectory();

    /**
     * Opens a resource bundled inside the ME Control Center distribution (e.g. the web UI).
     * The path is relative and uses forward slashes. Thread-safe.
     */
    Optional<InputStream> openBundledResource(String path);

    /** Raw primitive for scheduling work on the server thread. Wrap it in a gateway before use. */
    ServerThreadExecutor serverThreadExecutor();

    /** Basic server information. Its methods are server-thread only. */
    ServerInfoPlatform serverInfo();

    /** Player identity and permissions. Its methods are server-thread only. */
    PlayerPlatform players();

    /** ME network discovery. Its methods are server-thread only. */
    NetworkPlatform networks();

    /** ME storage reads. Capture methods are server-thread only; see the interface. */
    StoragePlatform storage();

    /** Autocrafting. Methods marked {@link ServerThreadOnly} are server-thread only. */
    CraftingPlatform crafting();

    /** Pattern encoding and pattern providers. Methods marked {@link ServerThreadOnly} are server-thread only. */
    PatternPlatform patterns();

    /** Server recipes and registered resources. Methods marked {@link ServerThreadOnly} are server-thread only. */
    RecipePlatform recipes();

    /** Game and mod asset sources. Thread-safe. */
    AssetPlatform assets();

    /** AE2 integration boundary. */
    Ae2Platform ae2();
}
