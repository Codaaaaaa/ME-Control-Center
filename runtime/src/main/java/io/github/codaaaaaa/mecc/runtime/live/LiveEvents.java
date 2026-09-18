package io.github.codaaaaaa.mecc.runtime.live;

import io.github.codaaaaaa.mecc.core.auth.Session;
import io.github.codaaaaaa.mecc.core.crafting.CraftingOrder;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import io.github.codaaaaaa.mecc.core.live.LiveEvent;
import io.github.codaaaaaa.mecc.core.live.LiveEventService;
import io.github.codaaaaaa.mecc.core.networks.GridStatus;
import io.github.codaaaaaa.mecc.core.networks.NetworkReconciler.Resolution;
import io.github.codaaaaaa.mecc.core.permissions.NetworkAccess;
import io.github.codaaaaaa.mecc.core.permissions.NetworkCapability;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform.CpuCapture;
import io.github.codaaaaaa.mecc.platform.CraftingPlatform.CpuState;
import io.github.codaaaaaa.mecc.runtime.crafting.CpuSnapshots;
import io.github.codaaaaaa.mecc.runtime.crafting.CraftingPresenter;
import io.github.codaaaaaa.mecc.runtime.crafting.CraftingTracker;
import io.github.codaaaaaa.mecc.runtime.crafting.UserCache;
import io.github.codaaaaaa.mecc.runtime.networks.NetworkDirectory;
import io.github.codaaaaaa.mecc.runtime.resources.DefaultResourceService;
import io.github.codaaaaaa.mecc.runtime.networks.NetworkGuard;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pushes live network, CPU, and order updates to connected browsers (spec section 31).
 *
 * <ul>
 *   <li>A connection only receives events about networks it subscribed to, and subscribing checks access
 *       exactly like a REST request would. Access is re-checked every {@link #ACCESS_RECHECK}; a subscription
 *       whose access is gone ends with {@code subscription.ended}.</li>
 *   <li>Payloads are built per viewer, because what a viewer may cancel depends on their role.</li>
 *   <li>CPU lists are pushed only when they change; networks with viewers are polled by the order tracker,
 *       so viewers share one CPU capture per network.</li>
 * </ul>
 */
public final class LiveEvents implements LiveEventService, CraftingTracker.Listener {
    private static final Logger LOGGER = LoggerFactory.getLogger(LiveEvents.class);
    static final Duration ACCESS_RECHECK = Duration.ofSeconds(30);
    /** Subscriptions per connection; a browser tab needs one or two. */
    static final int MAX_SUBSCRIPTIONS = 8;

    private final NetworkGuard guard;
    private final CraftingPresenter presenter;
    private final CpuSnapshots cpus;
    private final UserCache users;
    private final Clock clock;
    private final Set<LiveConnection> connections = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> cpuSignatures = new ConcurrentHashMap<>();
    private final Map<UUID, String> statusSignatures = new ConcurrentHashMap<>();

    public LiveEvents(NetworkGuard guard, CraftingPresenter presenter, CpuSnapshots cpus, UserCache users, Clock clock) {
        this.guard = guard;
        this.presenter = presenter;
        this.cpus = cpus;
        this.users = users;
        this.clock = clock;
    }

    public void start(ScheduledExecutorService scheduler, NetworkDirectory directory) {
        directory.addListener(this::directoryChanged);
        scheduler.scheduleWithFixedDelay(this::recheckAccess, ACCESS_RECHECK.toSeconds(), ACCESS_RECHECK.toSeconds(),
                TimeUnit.SECONDS);
    }

    public void stop() {
        new ArrayList<>(connections).forEach(LiveConnection::close);
    }

    /** Networks at least one connection is watching. */
    public Set<UUID> subscribedNetworks() {
        Set<UUID> networks = new HashSet<>();
        connections.forEach(connection -> networks.addAll(connection.subscriptions.keySet()));
        return networks;
    }

    public int connectionCount() {
        return connections.size();
    }

    @Override
    public Connection connect(Session session, Sink sink) {
        LiveConnection connection = new LiveConnection(session, sink);
        connections.add(connection);
        connection.send(new LiveEvent(LiveEvent.SESSION_READY, clock.instant(), null,
                Map.of("user", session.user().playerUuid().toString())));
        return connection;
    }

    // --- events -------------------------------------------------------------------------------------

    @Override
    public void orderChanged(CraftingOrder order, String eventType) {
        for (LiveConnection connection : connections) {
            Subscription subscription = connection.subscriptions.get(order.networkId());
            if (subscription == null) {
                continue;
            }
            users.views(CraftingPresenter.users(List.of(order))).thenAccept(names -> connection.send(new LiveEvent(
                    eventType, clock.instant(), order.networkId(),
                    presenter.order(order, subscription.access, connection.viewer(), names, subscription.locale))));
        }
    }

    @Override
    public void cpusCaptured(UUID networkId, CpuCapture capture) {
        String signature = signature(capture);
        if (signature.equals(cpuSignatures.put(networkId, signature))) {
            return;
        }
        sendCpus(networkId, capture, null);
    }

    /** Sends a CPU list to every subscriber of a network, or only to {@code only}. */
    private void sendCpus(UUID networkId, CpuCapture capture, LiveConnection only) {
        List<LiveConnection> targets = new ArrayList<>();
        for (LiveConnection connection : connections) {
            if ((only == null || only == connection) && connection.subscriptions.containsKey(networkId)) {
                targets.add(connection);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        users.views(presenter.users(networkId, capture)).thenAccept(names -> {
            for (LiveConnection connection : targets) {
                Subscription subscription = connection.subscriptions.get(networkId);
                if (subscription != null) {
                    connection.send(new LiveEvent(LiveEvent.CPU_UPDATED, clock.instant(), networkId,
                            presenter.cpus(networkId, capture, subscription.access, connection.viewer(), names,
                                    subscription.locale)));
                }
            }
        });
    }

    /** What makes a CPU list worth re-sending: state, jobs, and progress to a tenth of a percent. */
    static String signature(CpuCapture capture) {
        StringBuilder text = new StringBuilder();
        for (CpuState cpu : capture.cpus()) {
            text.append(cpu.id()).append(cpu.busy()).append(cpu.online()).append(cpu.storageBytes())
                    .append(cpu.coProcessors()).append(cpu.selectionMode()).append(Objects.hashCode(cpu.name()));
            if (cpu.job() != null) {
                text.append(cpu.job().jobId()).append(cpu.job().output().id()).append(cpu.job().amount())
                        .append(cpu.job().progress() == null ? "-" : Math.round(cpu.job().progress() * 1000));
            }
            text.append(';');
        }
        return text.toString();
    }

    private void directoryChanged(NetworkDirectory.State state) {
        Map<UUID, String> current = new HashMap<>();
        state.records().keySet().forEach(id -> current.put(id, statusSignature(state.result().resolutions().get(id))));
        for (Map.Entry<UUID, String> entry : current.entrySet()) {
            String previous = statusSignatures.put(entry.getKey(), entry.getValue());
            if (previous != null && !previous.equals(entry.getValue())) {
                broadcast(entry.getKey(), new LiveEvent(LiveEvent.NETWORK_STATUS_CHANGED, clock.instant(), entry.getKey(),
                        Map.of("networkId", entry.getKey().toString())));
            }
        }
        statusSignatures.keySet().retainAll(current.keySet());
    }

    private static String statusSignature(Resolution resolution) {
        if (resolution == null) {
            return "none";
        }
        StringBuilder text = new StringBuilder(resolution.status().name()).append(resolution.reason());
        if (resolution.grid() != null) {
            GridStatus status = resolution.grid().status();
            text.append(status.powered()).append(status.booting()).append(status.controllerState())
                    .append(status.busyCraftingCpus()).append(status.craftingCpus());
        }
        return text.toString();
    }

    /** Sends an event about a network to everyone subscribed to it, e.g. a deployed pattern. */
    public void networkEvent(UUID networkId, LiveEvent event) {
        broadcast(networkId, event);
    }

    /** Sends an event to every connection of one player, whatever they subscribed to (e.g. their alerts). */
    public void playerEvent(UUID player, LiveEvent event) {
        for (LiveConnection connection : connections) {
            if (connection.viewer().equals(player)) {
                connection.send(event);
            }
        }
    }

    private void broadcast(UUID networkId, LiveEvent event) {
        for (LiveConnection connection : connections) {
            if (connection.subscriptions.containsKey(networkId)) {
                connection.send(event);
            }
        }
    }

    private void recheckAccess() {
        for (LiveConnection connection : connections) {
            for (UUID networkId : Set.copyOf(connection.subscriptions.keySet())) {
                guard.access(connection.session, networkId).whenComplete((access, error) -> {
                    Subscription subscription = connection.subscriptions.get(networkId);
                    if (subscription == null) {
                        return;
                    }
                    if (error == null && access.allows(NetworkCapability.VIEW_NETWORK)) {
                        subscription.access = access;
                    } else if (error != null && !(unwrap(error) instanceof MeccException)) {
                        LOGGER.debug("Could not re-check live access: {}", error.toString());
                    } else {
                        connection.end(networkId, "ACCESS_REVOKED");
                    }
                });
            }
        }
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    // --- connections --------------------------------------------------------------------------------

    private static final class Subscription {
        private final String locale;
        private volatile NetworkAccess access;

        private Subscription(String locale, NetworkAccess access) {
            this.locale = locale;
            this.access = access;
        }
    }

    private final class LiveConnection implements Connection {
        private volatile Session session;
        private final Sink sink;
        private final Map<UUID, Subscription> subscriptions = new ConcurrentHashMap<>();
        private volatile boolean closed;

        private LiveConnection(Session session, Sink sink) {
            this.session = session;
            this.sink = sink;
        }

        private UUID viewer() {
            return session.user().playerUuid();
        }

        @Override
        public CompletionStage<Void> subscribe(UUID networkId, String locale) {
            if (closed) {
                return CompletableFuture.completedFuture(null);
            }
            String resolvedLocale = locale != null && DefaultResourceService.LOCALES.contains(locale) ? locale : "en_us";
            return guard.access(session, networkId).thenAccept(access -> {
                NetworkGuard.require(access, NetworkCapability.VIEW_NETWORK);
                if (!subscriptions.containsKey(networkId) && subscriptions.size() >= MAX_SUBSCRIPTIONS) {
                    throw MeccException.validation("networkId", "Too many subscriptions on one connection");
                }
                subscriptions.put(networkId, new Subscription(resolvedLocale, access));
                send(new LiveEvent(LiveEvent.SUBSCRIBED, clock.instant(), networkId, Map.of("networkId", networkId.toString())));
                // Start the viewer with the current CPU list instead of waiting for the next change.
                CpuCapture cached = cpus.cached(networkId);
                if (cached != null) {
                    sendCpus(networkId, cached, this);
                }
            });
        }

        @Override
        public void unsubscribe(UUID networkId) {
            subscriptions.remove(networkId);
        }

        @Override
        public void refresh(Session newSession) {
            this.session = newSession;
        }

        private void end(UUID networkId, String reason) {
            if (subscriptions.remove(networkId) != null) {
                send(new LiveEvent(LiveEvent.SUBSCRIPTION_ENDED, clock.instant(), networkId, Map.of("reason", reason)));
            }
        }

        private void send(LiveEvent event) {
            if (closed) {
                return;
            }
            try {
                sink.send(event);
            } catch (RuntimeException e) {
                LOGGER.debug("Dropping live connection after a send failure: {}", e.toString());
                close();
            }
        }

        @Override
        public void close() {
            closed = true;
            subscriptions.clear();
            connections.remove(this);
        }
    }
}
