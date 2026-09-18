package io.github.codaaaaaa.mecc.forge;

import io.github.codaaaaaa.mecc.core.status.PlatformInfo;
import io.github.codaaaaaa.mecc.forge.ae2.Ae2CraftingPlatform;
import io.github.codaaaaaa.mecc.forge.ae2.Ae2Integration;
import io.github.codaaaaaa.mecc.forge.ae2.Ae2NetworkPlatform;
import io.github.codaaaaaa.mecc.forge.ae2.Ae2StoragePlatform;
import io.github.codaaaaaa.mecc.platform.Ae2Platform;
import io.github.codaaaaaa.mecc.platform.AssetPlatform;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform;
import io.github.codaaaaaa.mecc.platform.NetworkPlatform;
import io.github.codaaaaaa.mecc.platform.PlayerPlatform;
import io.github.codaaaaaa.mecc.platform.ServerInfoPlatform;
import io.github.codaaaaaa.mecc.platform.StoragePlatform;
import io.github.codaaaaaa.mecc.platform.MeccPlatform;
import io.github.codaaaaaa.mecc.platform.thread.ServerThreadExecutor;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.versions.forge.ForgeVersion;

/** Minecraft 1.20.1 / Forge 47 implementation of {@link MeccPlatform}. Construct on the server thread. */
final class ForgePlatform implements MeccPlatform {
    static final String PLATFORM_ID = "forge-1.20.1";

    private final PlatformInfo info;
    private final String meccVersion;
    private final Path configDirectory;
    private final IModFileInfo modFile;
    private final ServerThreadExecutor serverThreadExecutor;
    private final ServerInfoPlatform serverInfo;
    private final PlayerPlatform players;
    private final NetworkPlatform networks;
    private final Ae2StoragePlatform storage;
    private final CraftingPlatform crafting;
    private final AssetPlatform assets;
    private final Path dataDirectory;
    private final Ae2Platform ae2;

    ForgePlatform(MinecraftServer server) {
        this.info = new PlatformInfo(PLATFORM_ID, SharedConstants.getCurrentVersion().getName(), "forge", ForgeVersion.getVersion());
        this.meccVersion = ModList.get().getModContainerById(MeccForge.MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown");
        this.configDirectory = FMLPaths.CONFIGDIR.get().resolve(MeccForge.MOD_ID);
        // Inside the world save: network anchors refer to this world, and world backups include ME Control Center data.
        this.dataDirectory = server.getWorldPath(LevelResource.ROOT).resolve(MeccForge.MOD_ID).toAbsolutePath().normalize();
        this.modFile = ModList.get().getModFileById(MeccForge.MOD_ID);
        this.serverThreadExecutor = new ForgeServerThreadExecutor(server);
        this.serverInfo = new ForgeServerInfo(server);
        this.players = new ForgePlayers(server);
        this.networks = new Ae2NetworkPlatform(server);
        this.storage = new Ae2StoragePlatform(server);
        this.crafting = new Ae2CraftingPlatform(server, storage);
        this.assets = new ForgeAssets();
        this.ae2 = new Ae2Integration();
    }

    @Override
    public Path dataDirectory() {
        return dataDirectory;
    }

    @Override
    public PlayerPlatform players() {
        return players;
    }

    @Override
    public NetworkPlatform networks() {
        return networks;
    }

    @Override
    public StoragePlatform storage() {
        return storage;
    }

    @Override
    public CraftingPlatform crafting() {
        return crafting;
    }

    @Override
    public AssetPlatform assets() {
        return assets;
    }

    @Override
    public PlatformInfo info() {
        return info;
    }

    @Override
    public String meccVersion() {
        return meccVersion;
    }

    @Override
    public Path configDirectory() {
        return configDirectory;
    }

    @Override
    public Optional<InputStream> openBundledResource(String path) {
        // Resolve through Forge's mod file system: works for production jars and merged dev sources.
        Path resource = modFile.getFile().findResource(path.split("/"));
        if (!Files.isRegularFile(resource)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.newInputStream(resource));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not open bundled resource " + path, e);
        }
    }

    @Override
    public ServerThreadExecutor serverThreadExecutor() {
        return serverThreadExecutor;
    }

    @Override
    public ServerInfoPlatform serverInfo() {
        return serverInfo;
    }

    @Override
    public Ae2Platform ae2() {
        return ae2;
    }
}
