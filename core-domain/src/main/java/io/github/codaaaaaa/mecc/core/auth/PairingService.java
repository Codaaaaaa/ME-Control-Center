package io.github.codaaaaaa.mecc.core.auth;

import io.github.codaaaaaa.mecc.core.error.ErrorCode;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import io.github.codaaaaaa.mecc.core.security.RateLimiter;
import io.github.codaaaaaa.mecc.core.users.PlayerProfile;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and redeems one-time pairing keys (spec section 27.1). Keys live in memory only.
 *
 * <ul>
 *   <li>generated from {@link SecureRandom};</li>
 *   <li>tied to one player; issuing a new key invalidates that player's previous key;</li>
 *   <li>expire after the configured TTL;</li>
 *   <li>single use: {@link #redeem} removes the key before returning it;</li>
 *   <li>issuing is rate-limited per player.</li>
 * </ul>
 *
 * <p>Thread-safe and cheap: {@link #issue} is called on the Minecraft server thread.
 */
public final class PairingService {
    static final int MAX_KEYS_PER_WINDOW = 5;
    static final Duration ISSUE_WINDOW = Duration.ofMinutes(5);

    private final Duration ttl;
    private final Clock clock;
    private final SecureRandom random;
    private final RateLimiter issueLimiter;
    private final Map<String, PairingKey> byKey = new HashMap<>();
    private final Map<UUID, String> keyByPlayer = new HashMap<>();

    public PairingService(Duration ttl, Clock clock, SecureRandom random) {
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
        this.issueLimiter = new RateLimiter(MAX_KEYS_PER_WINDOW, ISSUE_WINDOW, 10_000, clock);
    }

    /**
     * An issued key.
     *
     * @param key       display form, e.g. {@code AB7K-3M2Q-9RXF}
     * @param player    the player the key pairs a device for
     * @param expiresAt instant after which the key is rejected
     */
    public record PairingKey(String key, PlayerProfile player, Instant expiresAt) {
    }

    public synchronized PairingKey issue(PlayerProfile player) {
        if (!issueLimiter.tryAcquire(player.uuid().toString())) {
            throw new MeccException(ErrorCode.RATE_LIMITED, "Too many pairing keys requested. Try again in a few minutes.");
        }
        purgeExpired();
        String previous = keyByPlayer.remove(player.uuid());
        if (previous != null) {
            byKey.remove(previous);
        }
        String display;
        String normalized;
        do {
            display = SecretCodes.newPairingKey(random);
            normalized = SecretCodes.normalizePairingKey(display);
        } while (byKey.containsKey(normalized));

        PairingKey issued = new PairingKey(display, player, clock.instant().plus(ttl));
        byKey.put(normalized, issued);
        keyByPlayer.put(player.uuid(), normalized);
        return issued;
    }

    /** Consumes a key. Empty when the key is malformed, unknown, expired, or already used. */
    public synchronized Optional<PairingKey> redeem(String input) {
        String normalized = SecretCodes.normalizePairingKey(input);
        if (normalized == null) {
            return Optional.empty();
        }
        PairingKey key = byKey.remove(normalized);
        if (key == null) {
            return Optional.empty();
        }
        keyByPlayer.remove(key.player().uuid(), normalized);
        if (!clock.instant().isBefore(key.expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(key);
    }

    /** Number of unexpired outstanding keys (diagnostics/tests). */
    public synchronized int outstandingKeys() {
        purgeExpired();
        return byKey.size();
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        byKey.entrySet().removeIf(entry -> {
            boolean expired = !now.isBefore(entry.getValue().expiresAt());
            if (expired) {
                keyByPlayer.remove(entry.getValue().player().uuid(), entry.getKey());
            }
            return expired;
        });
    }
}
