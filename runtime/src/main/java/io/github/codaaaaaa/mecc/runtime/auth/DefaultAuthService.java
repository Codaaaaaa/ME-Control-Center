package io.github.codaaaaaa.mecc.runtime.auth;

import io.github.codaaaaaa.mecc.core.audit.AuditAction;
import io.github.codaaaaaa.mecc.core.audit.AuditEvent;
import io.github.codaaaaaa.mecc.core.audit.AuditEvent.AuditResult;
import io.github.codaaaaaa.mecc.core.auth.AuthService;
import io.github.codaaaaaa.mecc.core.auth.AuthViews.DeviceView;
import io.github.codaaaaaa.mecc.core.auth.AuthViews.MeView;
import io.github.codaaaaaa.mecc.core.auth.AuthViews.UserView;
import io.github.codaaaaaa.mecc.core.auth.ClientInfo;
import io.github.codaaaaaa.mecc.core.auth.Device;
import io.github.codaaaaaa.mecc.core.auth.DeviceNames;
import io.github.codaaaaaa.mecc.core.auth.PairingService;
import io.github.codaaaaaa.mecc.core.auth.PairingService.PairingKey;
import io.github.codaaaaaa.mecc.core.auth.SecretCodes;
import io.github.codaaaaaa.mecc.core.auth.Session;
import io.github.codaaaaaa.mecc.core.error.ErrorCode;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import io.github.codaaaaaa.mecc.core.persistence.DataStore;
import io.github.codaaaaaa.mecc.core.persistence.DuplicateKeyException;
import io.github.codaaaaaa.mecc.core.persistence.Repositories;
import io.github.codaaaaaa.mecc.core.security.RateLimiter;
import io.github.codaaaaaa.mecc.core.text.DisplayText;
import io.github.codaaaaaa.mecc.core.users.PlayerProfile;
import io.github.codaaaaaa.mecc.core.users.WebUser;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Pairing, device-token authentication, and device management backed by the {@link DataStore}. */
public final class DefaultAuthService implements AuthService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultAuthService.class);

    /** Pairing attempts per client address. */
    static final int PAIR_ATTEMPTS_PER_ADDRESS = 10;
    static final Duration PAIR_WINDOW = Duration.ofMinutes(5);
    /** Server-wide pairing attempts, bounding distributed guessing. */
    static final int PAIR_ATTEMPTS_GLOBAL = 120;
    /** {@code last_used_at} is written at most this often per device. */
    static final Duration TOUCH_INTERVAL = Duration.ofMinutes(5);
    /** The device cookie is re-sent at most this often to extend its lifetime. */
    static final Duration COOKIE_RENEW_INTERVAL = Duration.ofDays(1);

    private final DataStore store;
    private final PairingService pairing;
    private final AdminResolver admins;
    private final boolean adminOverride;
    private final Clock clock;
    private final SecureRandom random;
    private final RateLimiter pairLimiter;
    private final RateLimiter globalPairLimiter;

    public DefaultAuthService(DataStore store, PairingService pairing, AdminResolver admins, boolean adminOverride,
                              Clock clock, SecureRandom random) {
        this.store = store;
        this.pairing = pairing;
        this.admins = admins;
        this.adminOverride = adminOverride;
        this.clock = clock;
        this.random = random;
        this.pairLimiter = new RateLimiter(PAIR_ATTEMPTS_PER_ADDRESS, PAIR_WINDOW, 10_000, clock);
        this.globalPairLimiter = new RateLimiter(PAIR_ATTEMPTS_GLOBAL, Duration.ofMinutes(1), 1, clock);
    }

    @Override
    public CompletionStage<PairingOutcome> pair(String pairingKey, String requestedName, ClientInfo client) {
        String address = Objects.requireNonNullElse(client.address(), "unknown");
        if (!pairLimiter.tryAcquire(address) || !globalPairLimiter.tryAcquire("global")) {
            return CompletableFuture.failedFuture(new MeccException(ErrorCode.RATE_LIMITED,
                    "Too many pairing attempts. Wait a few minutes and try again."));
        }
        Optional<PairingKey> redeemed = pairing.redeem(pairingKey);
        if (redeemed.isEmpty()) {
            return CompletableFuture.failedFuture(new MeccException(ErrorCode.PAIRING_KEY_INVALID,
                    "The pairing key is invalid, expired, or already used. Run /mecc pair in game for a new one."));
        }
        PlayerProfile player = redeemed.get().player();
        String name = DisplayText.sanitize(requestedName, Device.MAX_NAME_LENGTH);
        String deviceName = name.isEmpty() ? DeviceNames.fromUserAgent(client.userAgent()) : name;
        String token = SecretCodes.newDeviceToken(random);
        String tokenHash = SecretCodes.hashToken(token);

        return store.write(repos -> {
                    Instant now = clock.instant();
                    WebUser user = repos.users().upsert(player, now);
                    repos.users().touchLastSeen(player.uuid(), now);
                    Device device = insertDevice(repos, player, deviceName, client, tokenHash, now);
                    audit(repos, now, player, device.id(), AuditAction.DEVICE_PAIR, device.id(), Map.of("name", deviceName));
                    return new Session(repos.users().find(player.uuid()).orElse(user), device, false, false);
                })
                .thenCompose(session -> admins.isAdmin(player)
                        .thenApply(admin -> new PairingOutcome(
                                new Session(session.user(), session.device(), admin, false), token)))
                .whenComplete((outcome, error) -> {
                    if (error == null) {
                        LOGGER.info("ME Control Center paired device {} for player {}", outcome.session().device().id(), player.name());
                    }
                });
    }

    private Device insertDevice(Repositories repos, PlayerProfile player, String name, ClientInfo client,
                                String tokenHash, Instant now) {
        for (int attempt = 0; ; attempt++) {
            Device device = new Device(SecretCodes.newDeviceId(random), player.uuid(), name, client.userAgent(),
                    now, now, client.address(), null);
            try {
                repos.devices().insert(device, tokenHash);
                return device;
            } catch (DuplicateKeyException e) {
                if (attempt >= 4) {
                    throw e;
                }
            }
        }
    }

    @Override
    public CompletionStage<Optional<Session>> authenticate(String token, ClientInfo client) {
        if (!SecretCodes.isPlausibleToken(token)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String tokenHash = SecretCodes.hashToken(token);
        return store.read(repos -> repos.devices().findActiveByTokenHash(tokenHash)
                        .flatMap(device -> repos.users().find(device.playerUuid())
                                .map(user -> new Session(user, device, false, false))))
                .thenCompose(found -> {
                    if (found.isEmpty()) {
                        return CompletableFuture.completedFuture(Optional.<Session>empty());
                    }
                    Session session = found.get();
                    Device device = session.device();
                    Instant now = clock.instant();
                    Duration idle = Duration.between(device.lastUsedAt(), now);
                    boolean renewCookie = idle.compareTo(COOKIE_RENEW_INTERVAL) >= 0;
                    if (idle.compareTo(TOUCH_INTERVAL) >= 0 || !Objects.equals(device.lastAddress(), client.address())) {
                        store.write(repos -> {
                            repos.devices().touch(device.id(), now, client.address());
                            repos.users().touchLastSeen(device.playerUuid(), now);
                            return null;
                        }).exceptionally(error -> {
                            LOGGER.warn("Could not record ME Control Center device activity", error);
                            return null;
                        });
                    }
                    PlayerProfile profile = new PlayerProfile(session.user().playerUuid(), session.user().playerName());
                    return admins.isAdmin(profile)
                            .thenApply(admin -> Optional.of(new Session(session.user(), device, admin, renewCookie)));
                });
    }

    @Override
    public CompletionStage<Void> logout(Session session) {
        return store.write(repos -> {
            Instant now = clock.instant();
            if (repos.devices().revoke(session.device().id(), now)) {
                audit(repos, now, session, AuditAction.DEVICE_REVOKE, session.device().id(), Map.of("reason", "logout"));
            }
            return null;
        });
    }

    @Override
    public CompletionStage<MeView> me(Session session) {
        return CompletableFuture.completedFuture(new MeView(
                new UserView(session.user().playerUuid(), session.user().playerName()),
                DeviceView.of(session.device(), session.device().id()),
                session.serverAdmin(),
                adminOverride));
    }

    @Override
    public CompletionStage<List<DeviceView>> listDevices(Session session) {
        return store.read(repos -> repos.devices().listActive(session.user().playerUuid()).stream()
                .map(device -> DeviceView.of(device, session.device().id()))
                .toList());
    }

    @Override
    public CompletionStage<DeviceView> renameDevice(Session session, String deviceId, String name) {
        String clean = DisplayText.sanitize(name, Device.MAX_NAME_LENGTH);
        if (clean.isEmpty()) {
            return CompletableFuture.failedFuture(MeccException.validation("name", "Device name must not be empty"));
        }
        return store.write(repos -> {
            Device device = ownActiveDevice(repos, session, deviceId);
            repos.devices().rename(device.id(), clean);
            audit(repos, clock.instant(), session, AuditAction.DEVICE_RENAME, device.id(), Map.of("name", clean));
            return DeviceView.of(repos.devices().find(device.id()).orElseThrow(), session.device().id());
        });
    }

    @Override
    public CompletionStage<Void> revokeDevice(Session session, String deviceId) {
        return store.write(repos -> {
            Device device = ownActiveDevice(repos, session, deviceId);
            Instant now = clock.instant();
            repos.devices().revoke(device.id(), now);
            audit(repos, now, session, AuditAction.DEVICE_REVOKE, device.id(), Map.of());
            return null;
        });
    }

    @Override
    public CompletionStage<Integer> revokeOtherDevices(Session session) {
        return store.write(repos -> {
            Instant now = clock.instant();
            int count = repos.devices().revokeAll(session.user().playerUuid(), session.device().id(), now);
            if (count > 0) {
                audit(repos, now, session, AuditAction.DEVICE_REVOKE, "all-except-current", Map.of("count", Integer.toString(count)));
            }
            return count;
        });
    }

    private static Device ownActiveDevice(Repositories repos, Session session, String deviceId) {
        return repos.devices().find(deviceId)
                .filter(Device::active)
                .filter(device -> device.playerUuid().equals(session.user().playerUuid()))
                .orElseThrow(() -> new MeccException(ErrorCode.DEVICE_NOT_FOUND, "No such device"));
    }

    private static void audit(Repositories repos, Instant now, Session session, AuditAction action, String target,
                              Map<String, String> parameters) {
        repos.audit().append(new AuditEvent(now, session.user().playerUuid(), session.device().id(), null, action,
                target, AuditResult.SUCCESS, false, parameters));
    }

    private static void audit(Repositories repos, Instant now, PlayerProfile player, String deviceId, AuditAction action,
                              String target, Map<String, String> parameters) {
        repos.audit().append(new AuditEvent(now, player.uuid(), deviceId, null, action, target, AuditResult.SUCCESS,
                false, parameters));
    }
}
