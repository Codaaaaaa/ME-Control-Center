package io.github.codaaaaaa.mecc.runtime.admin;

import io.github.codaaaaaa.mecc.core.admin.AdminService;
import io.github.codaaaaaa.mecc.core.admin.AdminViews.AdminOverview;
import io.github.codaaaaaa.mecc.core.admin.AdminViews.AuditEntryView;
import io.github.codaaaaaa.mecc.core.admin.AdminViews.AuditPage;
import io.github.codaaaaaa.mecc.core.admin.AdminViews.BackupView;
import io.github.codaaaaaa.mecc.core.admin.AdminViews.ContentPackView;
import io.github.codaaaaaa.mecc.core.audit.AuditAction;
import io.github.codaaaaaa.mecc.core.audit.AuditEvent;
import io.github.codaaaaaa.mecc.core.audit.AuditEvent.AuditResult;
import io.github.codaaaaaa.mecc.core.audit.AuditRepository.AuditRecord;
import io.github.codaaaaaa.mecc.core.auth.AuthViews.UserView;
import io.github.codaaaaaa.mecc.core.auth.Session;
import io.github.codaaaaaa.mecc.core.config.MeccConfig;
import io.github.codaaaaaa.mecc.core.error.ErrorCode;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import io.github.codaaaaaa.mecc.core.networks.WebNetwork;
import io.github.codaaaaaa.mecc.core.permissions.NetworkCapability;
import io.github.codaaaaaa.mecc.core.status.PlatformInfo;
import io.github.codaaaaaa.mecc.core.users.WebUser;
import io.github.codaaaaaa.mecc.persistence.sqlite.SqliteDataStore;
import io.github.codaaaaaa.mecc.runtime.networks.NetworkGuard;
import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Server administration and audit-log reads (spec section 35, milestone 6). */
public final class DefaultAdminService implements AdminService {
    private final SqliteDataStore store;
    private final NetworkGuard guard;
    private final MeccConfig config;
    private final String configFile;
    private final String meccVersion;
    private final PlatformInfo platform;
    private final Supplier<List<ContentPackView>> contentPacks;
    private final Clock clock;

    public DefaultAdminService(SqliteDataStore store, NetworkGuard guard, MeccConfig config, String configFile,
                               String meccVersion, PlatformInfo platform, Supplier<List<ContentPackView>> contentPacks,
                               Clock clock) {
        this.store = store;
        this.guard = guard;
        this.config = config;
        this.configFile = configFile;
        this.meccVersion = meccVersion;
        this.platform = platform;
        this.contentPacks = contentPacks;
        this.clock = clock;
    }

    @Override
    public CompletionStage<AdminOverview> overview(Session session) {
        requireAdmin(session);
        return store.info().thenApply(database -> new AdminOverview(meccVersion, platform, configFile, config, database,
                contentPacks.get()));
    }

    @Override
    public CompletionStage<BackupView> backup(Session session) {
        requireAdmin(session);
        return store.backup().handle((backup, error) -> {
            AuditEvent event = new AuditEvent(clock.instant(), session.user().playerUuid(), session.device().id(), null,
                    AuditAction.DATABASE_BACKUP, backup == null ? null : backup.name(),
                    error == null ? AuditResult.SUCCESS : AuditResult.FAILED, false, Map.of());
            return store.write(repos -> {
                repos.audit().append(event);
                return null;
            }).thenApply(ignored -> {
                if (error != null) {
                    throw new MeccException(ErrorCode.INTERNAL_ERROR, "The database could not be backed up; see the server log");
                }
                return backup;
            });
        }).thenCompose(result -> result);
    }

    @Override
    public CompletionStage<AuditPage> auditLog(Session session, UUID networkId, Long before, int limit) {
        CompletableFuture<?> allowed;
        if (networkId == null) {
            requireAdmin(session);
            allowed = CompletableFuture.completedFuture(null);
        } else {
            allowed = guard.access(session, networkId)
                    .thenAccept(access -> NetworkGuard.require(access, NetworkCapability.VIEW_AUDIT_LOG));
        }
        return allowed.thenCompose(ignored -> store.read(repos -> {
            List<AuditRecord> records = repos.audit().list(networkId, before, limit + 1);
            boolean more = records.size() > limit;
            List<AuditRecord> page = more ? records.subList(0, limit) : records;

            Set<UUID> players = new HashSet<>();
            Map<UUID, String> networkNames = new HashMap<>();
            for (AuditRecord record : page) {
                AuditEvent event = record.event();
                if (event.actorPlayerUuid() != null) {
                    players.add(event.actorPlayerUuid());
                }
                UUID target = uuidOrNull(event.target());
                if (target != null) {
                    players.add(target);
                }
                if (event.networkId() != null && !networkNames.containsKey(event.networkId())) {
                    networkNames.put(event.networkId(),
                            repos.networks().find(event.networkId()).map(WebNetwork::displayName).orElse(null));
                }
            }
            Map<UUID, WebUser> users = repos.users().findAll(players);

            List<AuditEntryView> entries = page.stream().map(record -> {
                AuditEvent event = record.event();
                UUID target = uuidOrNull(event.target());
                return new AuditEntryView(record.id(), event.at(), user(event.actorPlayerUuid(), users),
                        event.deviceId(), event.networkId() == null ? null : event.networkId().toString(),
                        event.networkId() == null ? null : networkNames.get(event.networkId()), event.action(),
                        event.target(), target != null && users.containsKey(target) ? user(target, users) : null,
                        event.result(), event.adminOverride(), event.parameters());
            }).toList();
            return new AuditPage(entries, more ? page.get(page.size() - 1).id() : null);
        }));
    }

    private static void requireAdmin(Session session) {
        if (!session.serverAdmin()) {
            throw new MeccException(ErrorCode.PERMISSION_DENIED, "Only server admins may do this");
        }
    }

    private static UserView user(UUID id, Map<UUID, WebUser> users) {
        if (id == null) {
            return null;
        }
        WebUser user = users.get(id);
        return new UserView(id, user == null ? null : user.playerName());
    }

    private static UUID uuidOrNull(String text) {
        if (text == null || text.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
