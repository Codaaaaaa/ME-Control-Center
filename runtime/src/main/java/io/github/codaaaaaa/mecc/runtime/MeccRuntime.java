package io.github.codaaaaaa.mecc.runtime;

import io.github.codaaaaaa.mecc.assets.DefaultIconService;
import io.github.codaaaaaa.mecc.core.assets.IconService;
import io.github.codaaaaaa.mecc.core.auth.PairingService;
import io.github.codaaaaaa.mecc.core.command.ChatReply;
import io.github.codaaaaaa.mecc.core.command.CommandService;
import io.github.codaaaaaa.mecc.core.command.ServerText;
import io.github.codaaaaaa.mecc.core.concurrent.NamedThreadFactory;
import io.github.codaaaaaa.mecc.core.config.ConfigValidationException;
import io.github.codaaaaaa.mecc.core.config.ResourcesConfig;
import io.github.codaaaaaa.mecc.core.config.SecurityConfig;
import io.github.codaaaaaa.mecc.core.config.WebConfig;
import io.github.codaaaaaa.mecc.core.config.MeccConfig;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import io.github.codaaaaaa.mecc.core.live.LiveEventService;
import io.github.codaaaaaa.mecc.core.status.StatusService;
import io.github.codaaaaaa.mecc.core.users.PlayerProfile;
import io.github.codaaaaaa.mecc.persistence.sqlite.SqliteDataStore;
import io.github.codaaaaaa.mecc.platform.MeccPlatform;
import io.github.codaaaaaa.mecc.platform.thread.DefaultServerThreadGateway;
import io.github.codaaaaaa.mecc.runtime.assets.AssetCatalog;
import io.github.codaaaaaa.mecc.runtime.assets.ContentPacks;
import io.github.codaaaaaa.mecc.runtime.assets.VanillaAssets;
import io.github.codaaaaaa.mecc.runtime.auth.AdminResolver;
import io.github.codaaaaaa.mecc.runtime.auth.DefaultAuthService;
import io.github.codaaaaaa.mecc.runtime.commands.DefaultCommandService;
import io.github.codaaaaaa.mecc.runtime.config.ConfigLoader;
import io.github.codaaaaaa.mecc.runtime.crafting.CpuSnapshots;
import io.github.codaaaaaa.mecc.runtime.crafting.CraftingPresenter;
import io.github.codaaaaaa.mecc.runtime.crafting.CraftingTracker;
import io.github.codaaaaaa.mecc.runtime.crafting.DefaultCraftingService;
import io.github.codaaaaaa.mecc.runtime.crafting.PlanStore;
import io.github.codaaaaaa.mecc.runtime.crafting.ResourceLabels;
import io.github.codaaaaaa.mecc.runtime.crafting.UserCache;
import io.github.codaaaaaa.mecc.runtime.live.LiveEvents;
import io.github.codaaaaaa.mecc.runtime.networks.DefaultNetworkService;
import io.github.codaaaaaa.mecc.runtime.networks.NetworkDirectory;
import io.github.codaaaaaa.mecc.runtime.networks.NetworkGuard;
import io.github.codaaaaaa.mecc.runtime.resources.DefaultResourceService;
import io.github.codaaaaaa.mecc.runtime.resources.ResourceSnapshots;
import io.github.codaaaaaa.mecc.runtime.resources.TagIndex;
import io.github.codaaaaaa.mecc.runtime.status.DefaultStatusService;
import io.github.codaaaaaa.mecc.web.WebServer;
import io.github.codaaaaaa.mecc.web.api.ApiSecurity;
import io.github.codaaaaaa.mecc.web.rest.WebApi;
import io.github.codaaaaaa.mecc.web.staticfiles.StaticAssets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Version-independent ME Control Center lifecycle. A platform adapter creates one per Minecraft server
 * instance, calls {@link #start()} when the server starts and {@link #stop()} when it stops.
 *
 * <p>{@link #start()} returns immediately: configuration loading, database migration, asset loading, and
 * HTTP binding happen on an ME Control Center worker thread so the server thread is never blocked. Failures are
 * logged and never crash the Minecraft server.
 */
public final class MeccRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger(MeccRuntime.class);
    private static final int WORKER_THREADS = 4;
    /** Small and bounded: icons are rendered off the server thread, but never at its expense. */
    /** CPU lists are read at most this often per network, however many browsers and orders look at them. */
    private static final Duration CPU_SNAPSHOT_MAX_AGE = Duration.ofMillis(1500);
    private static final int ICON_THREADS = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));

    enum Phase {
        NEW,
        STARTING,
        RUNNING,
        FAILED,
        STOPPED
    }

    private final MeccPlatform platform;
    private final Clock clock;
    private final CommandService commandFacade = new CommandFacade();

    private ExecutorService workers;
    private ScheduledExecutorService scheduler;
    private DefaultServerThreadGateway gateway;
    private SqliteDataStore dataStore;
    private NetworkDirectory directory;
    private CraftingTracker craftingTracker;
    private PlanStore craftingPlans;
    private LiveEvents liveEvents;
    private AssetCatalog assets;
    private ThreadPoolExecutor iconWorkers;
    private WebServer webServer;
    private volatile CommandService commands;
    private volatile boolean webRunning;
    private volatile Phase phase = Phase.NEW;
    private CompletableFuture<Void> startup = CompletableFuture.completedFuture(null);

    public MeccRuntime(MeccPlatform platform) {
        this(platform, Clock.systemUTC());
    }

    MeccRuntime(MeccPlatform platform, Clock clock) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.clock = clock;
    }

    public synchronized void start() {
        if (phase != Phase.NEW) {
            throw new IllegalStateException("ME Control Center runtime already started");
        }
        phase = Phase.STARTING;
        Instant startedAt = clock.instant();
        LOGGER.info("Starting ME Control Center {} on {}", platform.meccVersion(), platform.info().platformId());

        workers = Executors.newFixedThreadPool(WORKER_THREADS, new NamedThreadFactory("ME Control Center-Worker"));
        scheduler = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("ME Control Center-Scheduler"));
        gateway = new DefaultServerThreadGateway(platform.serverThreadExecutor(), workers);
        StatusService statusService = new DefaultStatusService(platform, gateway, clock, startedAt);
        startup = CompletableFuture.runAsync(() -> startAsync(statusService), workers)
                .exceptionally(error -> {
                    phase = Phase.FAILED;
                    LOGGER.error("ME Control Center failed to start; the Minecraft server continues without ME Control Center", error);
                    return null;
                });
    }

    /** Completes when asynchronous startup has finished (successfully or not). For tests and diagnostics. */
    public synchronized CompletableFuture<Void> startupFuture() {
        return startup;
    }

    /** Port the web server is bound to, or -1 when it is not running. */
    public synchronized int webPort() {
        return webServer != null && webServer.isRunning() ? webServer.boundPort() : -1;
    }

    /**
     * Logic of the {@code /mecc} commands. Always usable: while ME Control Center is starting or unavailable, replies
     * explain that instead of failing.
     */
    public CommandService commands() {
        return commandFacade;
    }

    public void stop() {
        WebServer serverToStop;
        SqliteDataStore storeToClose;
        synchronized (this) {
            if (phase == Phase.NEW || phase == Phase.STOPPED) {
                return;
            }
            phase = Phase.STOPPED;
            serverToStop = webServer;
            webServer = null;
            storeToClose = dataStore;
            dataStore = null;
            commands = null;
            webRunning = false;
        }
        // The lock is released here: a still-running startup task needs it to notice the shutdown.
        LOGGER.info("Stopping ME Control Center");
        if (directory != null) {
            directory.stop();
        }
        if (craftingTracker != null) {
            craftingTracker.stop();
        }
        if (craftingPlans != null) {
            craftingPlans.clear();
        }
        if (liveEvents != null) {
            liveEvents.stop();
        }
        scheduler.shutdownNow();
        // Fail queued server-thread work first so in-flight HTTP requests answer immediately.
        gateway.close();
        if (serverToStop != null) {
            try {
                serverToStop.stop();
            } catch (Exception e) {
                LOGGER.warn("Error while stopping ME Control Center web server", e);
            }
        }
        workers.shutdown();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (iconWorkers != null) {
            iconWorkers.shutdownNow();
        }
        if (assets != null) {
            assets.close();
        }
        if (storeToClose != null) {
            storeToClose.close();
        }
    }

    private void startAsync(StatusService statusService) {
        ConfigLoader loader = new ConfigLoader(platform.configDirectory());
        MeccConfig config;
        try {
            config = loader.load();
        } catch (ConfigValidationException e) {
            LOGGER.error("ME Control Center not started. Fix {} and restart:", loader.file());
            e.problems().forEach(problem -> LOGGER.error("  - {}", problem));
            phase = Phase.FAILED;
            return;
        } catch (Exception e) {
            LOGGER.error("ME Control Center not started: could not read {}", loader.file(), e);
            phase = Phase.FAILED;
            return;
        }

        SqliteDataStore store;
        try {
            store = SqliteDataStore.open(platform.dataDirectory().resolve(SqliteDataStore.FILE_NAME));
        } catch (Exception e) {
            LOGGER.error("ME Control Center not started: could not open its database in {}", platform.dataDirectory(), e);
            phase = Phase.FAILED;
            return;
        }

        SecurityConfig security = config.security();
        SecureRandom random = new SecureRandom();
        Duration pairingTtl = Duration.ofSeconds(security.pairingKeyTtlSeconds());
        PairingService pairing = new PairingService(pairingTtl, clock, random);
        AdminResolver admins = new AdminResolver(platform.players(), gateway, security.adminOpLevel(), clock);
        DefaultAuthService auth = new DefaultAuthService(store, pairing, admins, security.adminOverride(), clock, random);
        NetworkDirectory networkDirectory = new NetworkDirectory(store, gateway, platform.networks(), clock);
        DefaultNetworkService networks = new DefaultNetworkService(store, networkDirectory, platform.players(), gateway,
                security.adminOverride(), clock);
        DefaultCommandService commandService = new DefaultCommandService(pairing, store, pairingTtl,
                security.adminOpLevel(), pairingUrl(config.web()), clock);

        // Icons and names come from static assets only; rendering never touches Minecraft state.
        AssetCatalog assetCatalog = new AssetCatalog(platform.assets().assetPacks(),
                new VanillaAssets(platform.configDirectory().resolve("cache"), platform.info().minecraftVersion()),
                new ContentPacks(platform.configDirectory().resolve("content-packs"), platform.info().minecraftVersion(),
                        platform.assets().modVersions()),
                platform.configDirectory().resolve("resourcepacks"));
        assetCatalog.rebuild();
        // Core threads, not maximum threads: with a queue this deep a pool that grows on demand would run
        // every icon of a freshly opened terminal through a single thread.
        ThreadPoolExecutor iconExecutor = new ThreadPoolExecutor(ICON_THREADS, ICON_THREADS, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(4096), new NamedThreadFactory("ME Control Center-Icons"));
        iconExecutor.allowCoreThreadTimeOut(true);
        IconService iconService = new DefaultIconService(assetCatalog::library, iconExecutor);

        ResourcesConfig resources = config.resources();
        ResourceSnapshots resourceSnapshots = new ResourceSnapshots(platform.storage(), gateway, workers, clock,
                Duration.ofSeconds(resources.snapshotMaxAgeSeconds()));
        NetworkGuard guard = new NetworkGuard(store, networkDirectory, security.adminOverride());
        DefaultResourceService resourceService = new DefaultResourceService(guard, resourceSnapshots,
                new TagIndex(platform.storage(), gateway), assetCatalog::names, platform.assets().modNames(),
                iconService, workers);

        // Crafting (spec sections 9-12) and live updates (section 31).
        ResourceLabels labels = new ResourceLabels(assetCatalog::names, platform.assets().modNames());
        UserCache userCache = new UserCache(store, clock);
        CraftingTracker tracker = new CraftingTracker(store, guard, labels, clock);
        CpuSnapshots cpuSnapshots = new CpuSnapshots(platform.crafting(), gateway, clock, CPU_SNAPSHOT_MAX_AGE,
                tracker::watchedJobs);
        PlanStore plans = new PlanStore(clock);
        CraftingPresenter presenter = new CraftingPresenter(labels, tracker, clock, iconService::assetVersion);
        DefaultCraftingService craftingService = new DefaultCraftingService(store, guard, platform.crafting(), gateway,
                cpuSnapshots, plans, tracker, presenter, labels, userCache, scheduler, workers, config.crafting(), clock);
        LiveEvents live = new LiveEvents(guard, presenter, cpuSnapshots, userCache, clock);
        tracker.connect(cpuSnapshots, live, live::subscribedNetworks);
        craftingService.onOrderEvent(live::orderChanged);

        synchronized (this) {
            if (phase == Phase.STOPPED) {
                // Stopped while starting: release everything created so far.
                iconExecutor.shutdownNow();
                assetCatalog.close();
                store.close();
                return;
            }
            dataStore = store;
            directory = networkDirectory;
            assets = assetCatalog;
            iconWorkers = iconExecutor;
            craftingTracker = tracker;
            craftingPlans = plans;
            liveEvents = live;

            commands = commandService;
            phase = Phase.RUNNING;
        }
        networkDirectory.start(scheduler, Duration.ofSeconds(config.networks().discoveryIntervalSeconds()));
        scheduler.scheduleWithFixedDelay(resourceSnapshots::evictIdle, 1, 1, TimeUnit.MINUTES);
        scheduler.scheduleWithFixedDelay(plans::purgeExpired, 1, 1, TimeUnit.MINUTES);
        live.start(scheduler, networkDirectory);
        tracker.start(scheduler).exceptionally(error -> {
            LOGGER.error("ME Control Center could not load active crafting orders; they are not tracked until restart", error);
            return null;
        });
        if (config.assets().downloadVanillaAssets()) {
            downloadVanillaAssets(assetCatalog);
        }

        WebConfig web = config.web();
        if (!web.enabled()) {
            LOGGER.info("ME Control Center web server disabled by configuration (web.enabled = false)");
            return;
        }
        startWebServer(web, security,
                new WebApi.Services(statusService, auth, networks, resourceService, iconService, craftingService), auth, live);
    }

    private void startWebServer(WebConfig web, SecurityConfig security, WebApi.Services services, DefaultAuthService auth,
                                LiveEventService live) {
        StaticAssets assets;
        try {
            assets = StaticAssets.load(platform::openBundledResource);
        } catch (Exception e) {
            LOGGER.error("ME Control Center web UI bundle is corrupt; serving the API only", e);
            assets = StaticAssets.empty();
        }
        if (assets.isEmpty()) {
            LOGGER.warn("ME Control Center web UI bundle not found in this build; serving the API only");
        }

        Set<String> allowedOrigins = new HashSet<>();
        security.allowedOrigins().forEach(origin -> allowedOrigins.add(WebConfig.originOf(origin)));
        if (web.publicOrigin() != null) {
            allowedOrigins.add(web.publicOrigin());
        }
        ApiSecurity apiSecurity = new ApiSecurity(security.trustedProxyRanges(), security.requireHttpsCookie(),
                allowedOrigins, ApiSecurity.DEFAULT_MAX_BODY_BYTES);

        WebServer server = new WebServer(web.host(), web.port(), web.maxThreads(), WebApi.routes(services), assets,
                apiSecurity, auth::authenticate, live);
        try {
            server.start();
        } catch (MeccException e) {
            LOGGER.error("{}. The Minecraft server continues without the ME Control Center web UI.", e.getMessage());
            return;
        }

        synchronized (this) {
            if (phase == Phase.STOPPED) {
                stopQuietly(server);
                return;
            }
            webServer = server;
            webRunning = true;
        }
        LOGGER.info("ME Control Center web UI listening on {}", describeAddress(web.host(), server.boundPort()));
    }

    /** Downloads vanilla client assets in the background; icons and names improve once it finishes. */
    private void downloadVanillaAssets(AssetCatalog catalog) {
        CompletableFuture.runAsync(() -> {
            try {
                if (catalog.downloadVanillaAssets()) {
                    LOGGER.info("ME Control Center reloaded assets with vanilla Minecraft content");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOGGER.warn("Could not download vanilla Minecraft assets; vanilla icons stay unavailable: {}", e.toString());
            }
        }, workers);
    }

    /** The URL shown by {@code /mecc pair}, or {@code null} when it cannot be known. */
    static String pairingUrl(WebConfig web) {
        if (!web.publicBaseUrl().isEmpty()) {
            return web.publicBaseUrl();
        }
        String host = web.host();
        if (host.equals("0.0.0.0") || host.equals("::")) {
            return null;
        }
        if (host.equalsIgnoreCase("localhost") || host.equals("::1")) {
            host = "127.0.0.1";
        }
        String printable = host.contains(":") ? "[" + host + "]" : host;
        return "http://" + printable + ":" + web.port() + "/";
    }

    private static void stopQuietly(WebServer server) {
        try {
            server.stop();
        } catch (Exception e) {
            LOGGER.debug("Error stopping ME Control Center web server during shutdown", e);
        }
    }

    private static String describeAddress(String host, int port) {
        if (host.equals("0.0.0.0") || host.equals("::")) {
            return "all interfaces, port " + port + " (http://<server-ip>:" + port + "/)";
        }
        String printable = host.contains(":") ? "[" + host + "]" : host;
        return "http://" + printable + ":" + port + "/";
    }

    /** Delegates to the real command service once running; explains the situation otherwise. */
    private final class CommandFacade implements CommandService {
        @Override
        public ChatReply pair(PlayerProfile player, String locale) {
            CommandService delegate = commands;
            if (delegate == null || !webRunning) {
                return unavailable(locale);
            }
            return delegate.pair(player, locale);
        }

        @Override
        public CompletionStage<ChatReply> listDevices(PlayerProfile actor, int permissionLevel, String targetPlayer, String locale) {
            CommandService delegate = commands;
            return delegate == null
                    ? CompletableFuture.completedFuture(unavailable(locale))
                    : delegate.listDevices(actor, permissionLevel, targetPlayer, locale);
        }

        @Override
        public CompletionStage<ChatReply> revoke(PlayerProfile actor, int permissionLevel, String deviceIdOrAll, String locale) {
            CommandService delegate = commands;
            return delegate == null
                    ? CompletableFuture.completedFuture(unavailable(locale))
                    : delegate.revoke(actor, permissionLevel, deviceIdOrAll, locale);
        }

        private ChatReply unavailable(String locale) {
            return ChatReply.error(ServerText.get(locale, phase == Phase.STARTING ? "mecc.starting" : "mecc.not_running"));
        }
    }
}
