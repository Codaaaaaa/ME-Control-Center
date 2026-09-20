package io.github.codaaaaaa.mecc.runtime.explorer;

import io.github.codaaaaaa.mecc.core.auth.Session;
import io.github.codaaaaaa.mecc.core.explorer.ExplorerService;
import io.github.codaaaaaa.mecc.core.permissions.NetworkCapability;
import io.github.codaaaaaa.mecc.platform.NetworkPlatform;
import io.github.codaaaaaa.mecc.platform.NetworkPlatform.DeviceCapture;
import io.github.codaaaaaa.mecc.platform.thread.ServerThreadGateway;
import io.github.codaaaaaa.mecc.runtime.crafting.ResourceLabels;
import io.github.codaaaaaa.mecc.runtime.networks.NetworkGuard;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Network Explorer (spec section 26). Walking a grid's nodes is the heaviest read ME Control Center makes, so a
 * capture is shared by every browser looking at the network and refreshed at most once per {@link #MAX_AGE}.
 */
public final class DefaultExplorerService implements ExplorerService {
    static final Duration MAX_AGE = Duration.ofSeconds(10);
    static final Duration CAPTURE_TIMEOUT = Duration.ofSeconds(5);

    private final NetworkGuard guard;
    private final NetworkPlatform networks;
    private final ServerThreadGateway gateway;
    private final ResourceLabels labels;
    private final Supplier<String> assetVersion;
    private final Clock clock;
    private final Map<UUID, Entry> captures = new ConcurrentHashMap<>();

    private static final class Entry {
        private String gridKey;
        private DeviceCapture latest;
        private CompletableFuture<DeviceCapture> inFlight;
    }

    public DefaultExplorerService(NetworkGuard guard, NetworkPlatform networks, ServerThreadGateway gateway,
                                  ResourceLabels labels, Supplier<String> assetVersion, Clock clock) {
        this.guard = guard;
        this.networks = networks;
        this.gateway = gateway;
        this.labels = labels;
        this.assetVersion = assetVersion;
        this.clock = clock;
    }

    @Override
    public CompletionStage<NetworkMap> map(Session session, UUID networkId, String locale) {
        return guard.liveGrid(session, networkId, NetworkCapability.VIEW_NETWORK)
                .thenCompose(gridKey -> latest(networkId, gridKey))
                .thenApply(capture -> view(capture, locale == null || locale.isBlank() ? "en_us" : locale));
    }

    private NetworkMap view(DeviceCapture capture, String locale) {
        List<DeviceGroup> devices = capture.groups().stream()
                .map(group -> new DeviceGroup(group.kind(),
                        group.item() == null ? null : labels.label(group.item(), locale), group.count(),
                        group.offline(), group.channels(), group.idlePower(), group.locations(), group.truncated()))
                // Most of a kind first, so the biggest parts of the network are at the top.
                .sorted(Comparator.comparing((DeviceGroup group) -> group.kind().ordinal())
                        .thenComparing(group -> -group.count()))
                .toList();
        return new NetworkMap(capture.capturedAt(), capture.status(), devices, capture.nodes(), capture.offlineNodes(),
                assetVersion.get());
    }

    /** A capture no older than {@link #MAX_AGE}; concurrent readers share one walk of the grid. */
    private CompletableFuture<DeviceCapture> latest(UUID networkId, String gridKey) {
        Entry entry = captures.computeIfAbsent(networkId, id -> new Entry());
        synchronized (entry) {
            DeviceCapture latest = entry.latest;
            if (latest != null && gridKey.equals(entry.gridKey)
                    && Duration.between(latest.capturedAt(), clock.instant()).compareTo(MAX_AGE) < 0) {
                return CompletableFuture.completedFuture(latest);
            }
            if (entry.inFlight != null && gridKey.equals(entry.gridKey)) {
                return entry.inFlight;
            }
            CompletableFuture<DeviceCapture> capture = gateway.call("networks.describeDevices",
                    () -> networks.describeDevices(gridKey), CAPTURE_TIMEOUT);
            entry.gridKey = gridKey;
            entry.latest = null;
            entry.inFlight = capture;
            capture.whenComplete((result, error) -> {
                synchronized (entry) {
                    if (entry.inFlight == capture) {
                        entry.inFlight = null;
                        if (result != null) {
                            entry.latest = result;
                        }
                    }
                }
            });
            return capture;
        }
    }
}
