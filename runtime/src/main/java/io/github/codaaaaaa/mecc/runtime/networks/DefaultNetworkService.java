package io.github.codaaaaaa.mecc.runtime.networks;

import io.github.codaaaaaa.mecc.core.audit.AuditAction;
import io.github.codaaaaaa.mecc.core.audit.AuditEvent;
import io.github.codaaaaaa.mecc.core.audit.AuditEvent.AuditResult;
import io.github.codaaaaaa.mecc.core.auth.AuthViews.UserView;
import io.github.codaaaaaa.mecc.core.auth.Session;
import io.github.codaaaaaa.mecc.core.error.ErrorCode;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import io.github.codaaaaaa.mecc.core.networks.BlockLocation;
import io.github.codaaaaaa.mecc.core.networks.DiscoverySnapshot.DiscoveredGrid;
import io.github.codaaaaaa.mecc.core.networks.DiscoverySnapshot.ObservedAnchor;
import io.github.codaaaaaa.mecc.core.networks.GridStatus;
import io.github.codaaaaaa.mecc.core.networks.NetworkAnchor;
import io.github.codaaaaaa.mecc.core.networks.NetworkMember;
import io.github.codaaaaaa.mecc.core.networks.NetworkReconciler.ConflictReason;
import io.github.codaaaaaa.mecc.core.networks.NetworkReconciler.Resolution;
import io.github.codaaaaaa.mecc.core.networks.NetworkRecordStatus;
import io.github.codaaaaaa.mecc.core.networks.NetworkService;
import io.github.codaaaaaa.mecc.core.networks.NetworkViews.AnchorView;
import io.github.codaaaaaa.mecc.core.networks.NetworkViews.CandidateView;
import io.github.codaaaaaa.mecc.core.networks.NetworkViews.MemberView;
import io.github.codaaaaaa.mecc.core.networks.NetworkViews.NetworkDetailView;
import io.github.codaaaaaa.mecc.core.networks.NetworkViews.NetworkState;
import io.github.codaaaaaa.mecc.core.networks.NetworkViews.NetworkSummaryView;
import io.github.codaaaaaa.mecc.core.networks.NetworkViews.StateReason;
import io.github.codaaaaaa.mecc.core.networks.WebNetwork;
import io.github.codaaaaaa.mecc.core.permissions.NetworkAccess;
import io.github.codaaaaaa.mecc.core.permissions.NetworkCapability;
import io.github.codaaaaaa.mecc.core.permissions.NetworkRole;
import io.github.codaaaaaa.mecc.core.persistence.DataStore;
import io.github.codaaaaaa.mecc.core.persistence.DuplicateKeyException;
import io.github.codaaaaaa.mecc.core.persistence.Repositories;
import io.github.codaaaaaa.mecc.core.text.DisplayText;
import io.github.codaaaaaa.mecc.core.users.PlayerProfile;
import io.github.codaaaaaa.mecc.core.users.WebUser;
import io.github.codaaaaaa.mecc.platform.PlayerPlatform;
import io.github.codaaaaaa.mecc.platform.thread.ServerThreadGateway;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;

/**
 * Network visibility, enrollment, and membership.
 *
 * <p>Every network-specific operation resolves {@link NetworkAccess} first. Callers without any access
 * get {@code NETWORK_NOT_FOUND}, indistinguishable from a network that does not exist, so network IDs
 * cannot be probed.
 */
public final class DefaultNetworkService implements NetworkService {
    private static final Pattern PLAYER_NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final Duration PLAYER_LOOKUP_TIMEOUT = Duration.ofSeconds(2);

    private final DataStore store;
    private final NetworkDirectory directory;
    private final PlayerPlatform players;
    private final ServerThreadGateway gateway;
    private final boolean adminOverride;
    private final Clock clock;

    public DefaultNetworkService(DataStore store, NetworkDirectory directory, PlayerPlatform players,
                                 ServerThreadGateway gateway, boolean adminOverride, Clock clock) {
        this.store = store;
        this.directory = directory;
        this.players = players;
        this.gateway = gateway;
        this.adminOverride = adminOverride;
        this.clock = clock;
    }

    @Override
    public CompletionStage<List<NetworkSummaryView>> list(Session session) {
        return store.read(repos -> {
            Map<UUID, NetworkRole> memberships = repos.networks().membershipsOf(uuid(session));
            List<WebNetwork> visible = new ArrayList<>();
            Map<UUID, NetworkAccess> access = new HashMap<>();
            for (WebNetwork network : repos.networks().listAll()) {
                NetworkAccess.resolve(memberships.get(network.id()), session.serverAdmin(), adminOverride)
                        .ifPresent(resolved -> {
                            visible.add(network);
                            access.put(network.id(), resolved);
                        });
            }
            Map<UUID, WebUser> owners = repos.users().findAll(visible.stream().map(WebNetwork::ownerPlayerUuid).toList());
            return visible.stream()
                    .map(network -> summary(network, access.get(network.id()), owners))
                    .sorted(Comparator.comparing(NetworkSummaryView::displayName, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        });
    }

    @Override
    public CompletionStage<List<CandidateView>> candidates(Session session) {
        // Always rediscover: players typically open this right after placing a Wireless Access Point.
        return directory.refresh().thenCompose(state -> {
            List<DiscoveredGrid> claimable = state.result().unenrolled().stream()
                    .filter(grid -> mayClaim(session, grid))
                    .toList();
            Set<UUID> owners = new HashSet<>();
            claimable.forEach(grid -> grid.anchors().forEach(anchor -> {
                if (anchor.ownerUuid() != null) owners.add(anchor.ownerUuid());
            }));
            return store.read(repos -> repos.users().findAll(owners)).thenApply(users -> claimable.stream()
                    .map(grid -> new CandidateView(
                            grid.anchors().get(0).location().key(),
                            grid.anchors().stream().map(anchor -> observedAnchorView(anchor, users)).toList(),
                            ownsAnchor(session, grid),
                            grid.status().powered(),
                            grid.status().nodeCount()))
                    .toList());
        });
    }

    @Override
    public CompletionStage<NetworkDetailView> claim(Session session, String candidateKey, String displayName) {
        String name = DisplayText.sanitize(displayName, WebNetwork.MAX_NAME_LENGTH);
        if (name.isEmpty()) {
            return CompletableFuture.failedFuture(MeccException.validation("displayName", "Network name must not be empty"));
        }
        BlockLocation key = BlockLocation.parseKey(candidateKey).orElse(null);
        if (key == null) {
            return CompletableFuture.failedFuture(candidateNotFound());
        }
        UUID networkId = UUID.randomUUID();
        return directory.<UUID>mutate(state -> {
            Optional<DiscoveredGrid> enrolledGrid = state.result().resolutions().values().stream()
                    .map(Resolution::grid)
                    .filter(Objects::nonNull)
                    .filter(grid -> containsAnchor(grid, key))
                    .findFirst();
            // Only callers who could otherwise claim it learn that it is already enrolled.
            if (enrolledGrid.isPresent() && mayClaim(session, enrolledGrid.get())) {
                throw new CompletionException(new MeccException(ErrorCode.CONFLICT,
                        "This ME network is already enrolled in ME Control Center"));
            }
            DiscoveredGrid grid = state.result().unenrolled().stream()
                    .filter(candidate -> containsAnchor(candidate, key))
                    .filter(candidate -> mayClaim(session, candidate))
                    .findFirst()
                    .orElseThrow(() -> new CompletionException(candidateNotFound()));
            boolean viaOverride = !ownsAnchor(session, grid);
            return store.write(repos -> {
                Instant now = clock.instant();
                // Stale anchors at these locations belong to other records; the refresh just pruned them.
                List<NetworkAnchor> anchors = grid.anchors().stream()
                        .map(anchor -> new NetworkAnchor(anchor.location(), anchor.ownerUuid(), now))
                        .toList();
                try {
                    repos.networks().insert(new WebNetwork(networkId, name, uuid(session), now, now,
                            NetworkRecordStatus.ONLINE, anchors));
                } catch (DuplicateKeyException e) {
                    throw new MeccException(ErrorCode.CONFLICT, "This ME network is already enrolled in ME Control Center");
                }
                repos.networks().upsertMember(new NetworkMember(networkId, uuid(session), NetworkRole.OWNER, null, now));
                audit(repos, session, networkId, AuditAction.NETWORK_CLAIM, key.key(), viaOverride,
                        Map.of("name", name, "anchors", Integer.toString(anchors.size())));
                return networkId;
            });
        }).thenCompose(id -> get(session, id));
    }

    @Override
    public CompletionStage<NetworkDetailView> get(Session session, UUID networkId) {
        return store.read(repos -> {
            Loaded loaded = load(repos, session, networkId);
            Set<UUID> userIds = new HashSet<>();
            userIds.add(loaded.network().ownerPlayerUuid());
            loaded.network().anchors().forEach(anchor -> {
                if (anchor.ownerUuid() != null) userIds.add(anchor.ownerUuid());
            });
            Map<UUID, WebUser> users = repos.users().findAll(userIds);

            // Read the directory once: status and its capture time must come from the same discovery pass.
            NetworkDirectory.State state = directory.current();
            Resolution resolution = state == null ? null : state.result().resolutions().get(networkId);
            DiscoveredGrid grid = resolution == null ? null : resolution.grid();
            Map<BlockLocation, ObservedAnchor> observed = new HashMap<>();
            if (grid != null) {
                grid.anchors().forEach(anchor -> observed.put(anchor.location(), anchor));
            }
            List<AnchorView> anchors = loaded.network().anchors().stream()
                    .map(anchor -> {
                        ObservedAnchor seen = observed.get(anchor.location());
                        return anchorView(anchor.location(), anchor.ownerUuid(), users, seen == null ? null : seen.active());
                    })
                    .toList();
            return new NetworkDetailView(
                    summary(loaded.network(), loaded.access(), users),
                    grid == null ? null : grid.status(),
                    grid == null ? null : state.snapshot().capturedAt(),
                    anchors,
                    loaded.access().capabilities());
        });
    }

    @Override
    public CompletionStage<NetworkSummaryView> rename(Session session, UUID networkId, String displayName) {
        String name = DisplayText.sanitize(displayName, WebNetwork.MAX_NAME_LENGTH);
        if (name.isEmpty()) {
            return CompletableFuture.failedFuture(MeccException.validation("name", "Network name must not be empty"));
        }
        return store.write(repos -> {
            Loaded loaded = load(repos, session, networkId);
            require(loaded.access(), NetworkCapability.RENAME_NETWORK);
            repos.networks().rename(networkId, name);
            audit(repos, session, networkId, AuditAction.NETWORK_RENAME, networkId.toString(),
                    loaded.access().requiresOverride(NetworkCapability.RENAME_NETWORK),
                    Map.of("from", loaded.network().displayName(), "to", name));
            WebNetwork renamed = repos.networks().find(networkId).orElseThrow();
            return summary(renamed, loaded.access(), repos.users().findAll(List.of(renamed.ownerPlayerUuid())));
        });
    }

    @Override
    public CompletionStage<Void> delete(Session session, UUID networkId) {
        return store.<Void>write(repos -> {
            Loaded loaded = load(repos, session, networkId);
            require(loaded.access(), NetworkCapability.DELETE_NETWORK);
            repos.networks().delete(networkId);
            audit(repos, session, networkId, AuditAction.NETWORK_DELETE, networkId.toString(),
                    loaded.access().requiresOverride(NetworkCapability.DELETE_NETWORK),
                    Map.of("name", loaded.network().displayName()));
            return null;
        }).thenCompose(ignored -> directory.recomputeLatest().thenApply(state -> null));
    }

    @Override
    public CompletionStage<List<MemberView>> members(Session session, UUID networkId) {
        return store.read(repos -> {
            Loaded loaded = load(repos, session, networkId);
            List<NetworkMember> members = repos.networks().members(networkId);
            Set<UUID> ids = new HashSet<>();
            members.forEach(member -> {
                ids.add(member.playerUuid());
                if (member.addedBy() != null) ids.add(member.addedBy());
            });
            Map<UUID, WebUser> users = repos.users().findAll(ids);
            return members.stream()
                    .map(member -> memberView(member, loaded.network(), users))
                    .sorted(Comparator.comparing((MemberView view) -> view.role()).reversed()
                            .thenComparing(view -> Objects.requireNonNullElse(view.user().playerName(), ""),
                                    String.CASE_INSENSITIVE_ORDER))
                    .toList();
        });
    }

    @Override
    public CompletionStage<MemberView> putMember(Session session, UUID networkId, String player, NetworkRole role) {
        Objects.requireNonNull(role, "role");
        // Check access before resolving the player, so outsiders learn nothing about player records.
        return store.read(repos -> {
                    Loaded loaded = load(repos, session, networkId);
                    require(loaded.access(), NetworkCapability.MANAGE_MEMBERS);
                    return findKnownPlayer(repos, player);
                })
                .thenCompose(known -> known.isPresent()
                        ? CompletableFuture.completedFuture(known.get())
                        : findOnlinePlayer(player))
                .thenCompose(target -> store.write(repos -> {
                    Instant now = clock.instant();
                    Loaded loaded = load(repos, session, networkId);
                    require(loaded.access(), NetworkCapability.MANAGE_MEMBERS);
                    if (target.uuid().equals(loaded.network().ownerPlayerUuid())) {
                        throw new MeccException(ErrorCode.CONFLICT, "The primary owner's role cannot be changed");
                    }
                    repos.users().upsert(target, now);
                    Optional<NetworkRole> previous = repos.networks().memberRole(networkId, target.uuid());
                    repos.networks().upsertMember(new NetworkMember(networkId, target.uuid(), role, uuid(session), now));
                    boolean override = loaded.access().requiresOverride(NetworkCapability.MANAGE_MEMBERS);
                    if (previous.isEmpty()) {
                        audit(repos, session, networkId, AuditAction.NETWORK_SHARE, target.uuid().toString(), override,
                                Map.of("player", target.name(), "role", role.name()));
                    } else if (previous.get() != role) {
                        audit(repos, session, networkId, AuditAction.NETWORK_MEMBER_ROLE_CHANGE, target.uuid().toString(),
                                override, Map.of("player", target.name(), "from", previous.get().name(), "to", role.name()));
                    }
                    NetworkMember stored = repos.networks().members(networkId).stream()
                            .filter(member -> member.playerUuid().equals(target.uuid()))
                            .findFirst()
                            .orElseThrow();
                    Set<UUID> ids = new HashSet<>(List.of(stored.playerUuid()));
                    if (stored.addedBy() != null) ids.add(stored.addedBy());
                    return memberView(stored, loaded.network(), repos.users().findAll(ids));
                }));
    }

    @Override
    public CompletionStage<Void> removeMember(Session session, UUID networkId, UUID playerUuid) {
        return store.write(repos -> {
            Loaded loaded = load(repos, session, networkId);
            boolean self = playerUuid.equals(uuid(session));
            if (!self) {
                require(loaded.access(), NetworkCapability.MANAGE_MEMBERS);
            }
            if (playerUuid.equals(loaded.network().ownerPlayerUuid())) {
                throw new MeccException(ErrorCode.CONFLICT, "The primary owner cannot be removed");
            }
            Optional<NetworkRole> previous = repos.networks().memberRole(networkId, playerUuid);
            if (previous.isEmpty() || !repos.networks().removeMember(networkId, playerUuid)) {
                throw new MeccException(ErrorCode.PLAYER_NOT_FOUND, "That player is not a member of this network");
            }
            // Their watchlist on this network is no longer theirs to see; stop sampling it.
            repos.watchlist().deleteAll(playerUuid, networkId);
            repos.alerts().deleteRules(playerUuid, networkId);
            repos.savedOrders().deleteAll(playerUuid, networkId);
            boolean override = !self && loaded.access().requiresOverride(NetworkCapability.MANAGE_MEMBERS);
            audit(repos, session, networkId, AuditAction.NETWORK_MEMBER_REMOVE, playerUuid.toString(), override,
                    Map.of("role", previous.get().name(), "self", Boolean.toString(self)));
            return null;
        });
    }

    // --- access -------------------------------------------------------------------------------------

    private record Loaded(WebNetwork network, NetworkAccess access) {
    }

    private Loaded load(Repositories repos, Session session, UUID networkId) {
        WebNetwork network = repos.networks().find(networkId).orElseThrow(DefaultNetworkService::networkNotFound);
        NetworkRole role = repos.networks().memberRole(networkId, uuid(session)).orElse(null);
        NetworkAccess access = NetworkAccess.resolve(role, session.serverAdmin(), adminOverride)
                .orElseThrow(DefaultNetworkService::networkNotFound);
        return new Loaded(network, access);
    }

    private static void require(NetworkAccess access, NetworkCapability capability) {
        if (!access.allows(capability)) {
            throw new MeccException(ErrorCode.PERMISSION_DENIED,
                    "Your role on this network does not allow this action",
                    Map.of("requiredRole", capability.minimumRole().name()));
        }
    }

    private boolean mayClaim(Session session, DiscoveredGrid grid) {
        return ownsAnchor(session, grid) || (session.serverAdmin() && adminOverride);
    }

    private static boolean ownsAnchor(Session session, DiscoveredGrid grid) {
        return grid.anchors().stream().anyMatch(anchor -> uuid(session).equals(anchor.ownerUuid()));
    }

    private static boolean containsAnchor(DiscoveredGrid grid, BlockLocation location) {
        return grid.anchors().stream().anyMatch(anchor -> anchor.location().equals(location));
    }

    // --- players ------------------------------------------------------------------------------------

    private static Optional<PlayerProfile> findKnownPlayer(Repositories repos, String player) {
        if (player == null || player.isBlank()) {
            throw MeccException.validation("player", "player is required");
        }
        String trimmed = player.strip();
        try {
            UUID id = UUID.fromString(trimmed);
            return Optional.of(repos.users().find(id)
                    .map(user -> new PlayerProfile(user.playerUuid(), user.playerName()))
                    .orElseThrow(DefaultNetworkService::playerNotFound));
        } catch (IllegalArgumentException notUuid) {
            if (!PLAYER_NAME.matcher(trimmed).matches()) {
                throw playerNotFound();
            }
            return repos.users().findByName(trimmed).map(user -> new PlayerProfile(user.playerUuid(), user.playerName()));
        }
    }

    private CompletableFuture<PlayerProfile> findOnlinePlayer(String name) {
        return gateway.call("players.findOnline", () -> players.findOnlinePlayer(name.strip()), PLAYER_LOOKUP_TIMEOUT)
                .thenApply(found -> found.orElseThrow(DefaultNetworkService::playerNotFound));
    }

    // --- views --------------------------------------------------------------------------------------

    private Resolution resolution(UUID networkId) {
        NetworkDirectory.State state = directory.current();
        return state == null ? null : state.result().resolutions().get(networkId);
    }

    private NetworkSummaryView summary(WebNetwork network, NetworkAccess access, Map<UUID, WebUser> users) {
        Resolution resolution = resolution(network.id());
        NetworkState state;
        StateReason reason;
        Instant lastSeen = network.lastSeenAt();
        if (resolution == null) {
            state = NetworkState.OFFLINE;
            reason = StateReason.NOT_DISCOVERED_YET;
        } else if (resolution.status() == NetworkRecordStatus.CONFLICT) {
            state = NetworkState.CONFLICT;
            reason = resolution.reason() == ConflictReason.SPLIT ? StateReason.SPLIT : StateReason.MERGED;
        } else if (resolution.status() == NetworkRecordStatus.OFFLINE) {
            state = NetworkState.OFFLINE;
            reason = StateReason.NOT_LOADED;
        } else {
            GridStatus status = resolution.grid().status();
            lastSeen = directory.current().snapshot().capturedAt();
            if (!status.powered()) {
                state = NetworkState.OFFLINE;
                reason = StateReason.UNPOWERED;
            } else if (GridStatus.CONTROLLER_CONFLICT.equals(status.controllerState())) {
                state = NetworkState.DEGRADED;
                reason = StateReason.CONTROLLER_CONFLICT;
            } else if (status.booting()) {
                state = NetworkState.DEGRADED;
                reason = StateReason.BOOTING;
            } else {
                state = NetworkState.ONLINE;
                reason = null;
            }
        }
        return new NetworkSummaryView(network.id(), network.displayName(),
                userView(network.ownerPlayerUuid(), users), access.effectiveRole(), access.adminOverride(),
                state, reason, lastSeen, network.createdAt());
    }

    private static MemberView memberView(NetworkMember member, WebNetwork network, Map<UUID, WebUser> users) {
        return new MemberView(userView(member.playerUuid(), users), member.role(),
                member.playerUuid().equals(network.ownerPlayerUuid()), member.addedAt(),
                member.addedBy() == null ? null : userView(member.addedBy(), users));
    }

    private static AnchorView observedAnchorView(ObservedAnchor anchor, Map<UUID, WebUser> users) {
        return anchorView(anchor.location(), anchor.ownerUuid(), users, anchor.active());
    }

    private static AnchorView anchorView(BlockLocation location, UUID owner, Map<UUID, WebUser> users, Boolean active) {
        return new AnchorView(location.key(), location.dimension(), location.x(), location.y(), location.z(),
                owner == null ? null : userView(owner, users), active);
    }

    private static UserView userView(UUID id, Map<UUID, WebUser> users) {
        WebUser user = users.get(id);
        return new UserView(id, user == null ? null : user.playerName());
    }

    private void audit(Repositories repos, Session session, UUID networkId, AuditAction action, String target,
                       boolean adminOverride, Map<String, String> parameters) {
        repos.audit().append(new AuditEvent(clock.instant(), uuid(session), session.device().id(), networkId, action,
                target, AuditResult.SUCCESS, adminOverride, parameters));
    }

    private static UUID uuid(Session session) {
        return session.user().playerUuid();
    }

    private static MeccException networkNotFound() {
        return new MeccException(ErrorCode.NETWORK_NOT_FOUND, "Network not found");
    }

    private static MeccException playerNotFound() {
        return new MeccException(ErrorCode.PLAYER_NOT_FOUND,
                "Unknown player. They must be online or have used ME Control Center before.");
    }

    private static MeccException candidateNotFound() {
        return new MeccException(ErrorCode.NETWORK_CANDIDATE_NOT_FOUND,
                "No unenrolled ME network you can claim contains that Wireless Access Point. Make sure its chunk is loaded.");
    }
}
