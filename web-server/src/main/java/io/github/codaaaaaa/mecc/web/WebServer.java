package io.github.codaaaaaa.mecc.web;

import io.github.codaaaaaa.mecc.core.error.ErrorCode;
import io.github.codaaaaaa.mecc.core.concurrent.NamedThreadFactory;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import io.github.codaaaaaa.mecc.core.live.LiveEventService;
import io.github.codaaaaaa.mecc.web.api.ApiHandler;
import io.github.codaaaaaa.mecc.web.api.ApiRoutes;
import io.github.codaaaaaa.mecc.web.api.ApiSecurity;
import io.github.codaaaaaa.mecc.web.api.SessionResolver;
import io.github.codaaaaaa.mecc.web.json.JsonCodec;
import io.github.codaaaaaa.mecc.web.staticfiles.StaticAssetHandler;
import io.github.codaaaaaa.mecc.web.staticfiles.StaticAssets;
import io.github.codaaaaaa.mecc.web.websocket.LiveSocketHandler;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;

/**
 * Embedded Jetty server hosting the ME Control Center API and web UI.
 *
 * <p>All Jetty threads are daemon threads named {@code ME Control Center-HTTP-*}. Nothing here may touch
 * Minecraft state; endpoints reach the game only through service ports backed by the
 * ServerThreadGateway.
 */
public final class WebServer {
    private static final long STOP_TIMEOUT_MILLIS = 3_000;
    private static final long IDLE_TIMEOUT_MILLIS = 30_000;
    private static final int MIN_THREADS = 4;

    private final String host;
    private final int port;
    private final int maxThreads;
    private final ApiRoutes routes;
    private final StaticAssets assets;
    private final ApiSecurity security;
    private final SessionResolver sessions;
    private final LiveEventService live;
    private Server server;
    private ScheduledExecutorService liveScheduler;
    private ServerConnector connector;

    /**
     * @param host       bind address
     * @param port       bind port; {@code 0} picks a free port (tests)
     * @param maxThreads HTTP pool size
     * @param security   request-trust settings
     * @param sessions   resolves device tokens on authenticated routes
     */
    public WebServer(String host, int port, int maxThreads, ApiRoutes routes, StaticAssets assets,
                     ApiSecurity security, SessionResolver sessions) {
        this(host, port, maxThreads, routes, assets, security, sessions, null);
    }

    /**
     * @param live live updates served on {@code /ws/v1}, or {@code null} for none
     */
    public WebServer(String host, int port, int maxThreads, ApiRoutes routes, StaticAssets assets,
                     ApiSecurity security, SessionResolver sessions, LiveEventService live) {
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.maxThreads = maxThreads;
        this.routes = Objects.requireNonNull(routes, "routes");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.security = Objects.requireNonNull(security, "security");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.live = live;
    }

    public synchronized void start() {
        if (server != null) {
            throw new IllegalStateException("ME Control Center web server already started");
        }
        QueuedThreadPool threadPool = new QueuedThreadPool(maxThreads, Math.min(MIN_THREADS, maxThreads));
        threadPool.setName("ME Control Center-HTTP");
        threadPool.setDaemon(true);

        Server jetty = new Server(threadPool);
        jetty.addBean(new ScheduledExecutorScheduler("ME Control Center-HTTP-Scheduler", true));
        jetty.setStopTimeout(STOP_TIMEOUT_MILLIS);
        jetty.setStopAtShutdown(false); // The Minecraft server lifecycle owns shutdown.

        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setSendServerVersion(false);
        httpConfig.setSendXPoweredBy(false);

        ServerConnector serverConnector = new ServerConnector(jetty, 1, 1, new HttpConnectionFactory(httpConfig));
        serverConnector.setHost(host);
        serverConnector.setPort(port);
        serverConnector.setIdleTimeout(IDLE_TIMEOUT_MILLIS);
        jetty.addConnector(serverConnector);

        ErrorHandler errorHandler = new ErrorHandler();
        errorHandler.setShowStacks(false);
        errorHandler.setShowMessageInTitle(false);
        jetty.setErrorHandler(errorHandler);

        JsonCodec json = new JsonCodec();
        Handler application = new Handler.Sequence(
                new ApiHandler(routes, json, security, sessions),
                new StaticAssetHandler(assets));
        ScheduledExecutorService scheduler = null;
        if (live != null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("ME Control Center-Live"));
            WebSocketUpgradeHandler upgrades = LiveSocketHandler.create(jetty, live, sessions, security, json, scheduler);
            upgrades.setHandler(application);
            application = upgrades;
        }

        GzipHandler gzip = new GzipHandler();
        gzip.setMinGzipSize(512);
        gzip.setHandler(new SecurityHeadersHandler(application));
        jetty.setHandler(gzip);

        try {
            jetty.start();
        } catch (Exception e) {
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
            try {
                jetty.stop();
            } catch (Exception suppressed) {
                e.addSuppressed(suppressed);
            }
            throw new MeccException(ErrorCode.INTERNAL_ERROR,
                    "Could not start ME Control Center web server on " + host + ":" + port + ": " + e.getMessage(), e);
        }
        server = jetty;
        connector = serverConnector;
        liveScheduler = scheduler;
    }

    /** Actual bound port (useful when started with port 0). */
    public synchronized int boundPort() {
        if (connector == null) {
            throw new IllegalStateException("ME Control Center web server not started");
        }
        return connector.getLocalPort();
    }

    public synchronized boolean isRunning() {
        return server != null && server.isRunning();
    }

    public synchronized void stop() throws Exception {
        if (server == null) {
            return;
        }
        try {
            server.stop();
        } finally {
            if (liveScheduler != null) {
                liveScheduler.shutdownNow();
            }
            server = null;
            connector = null;
            liveScheduler = null;
        }
    }
}
