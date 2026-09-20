package io.github.codaaaaaa.mecc.core.automation;

import io.github.codaaaaaa.mecc.core.crafting.OrderTarget;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One Keep Stock rule of a network (spec section 25): when the stored amount falls below {@code minimum},
 * ME Control Center may craft up to {@code restockTo}.
 *
 * <p>Rules belong to the network, not to one player: every Manager of the network sees and edits them, because
 * they spend the network's resources. {@code createdBy} is the player the crafting jobs are requested as.
 *
 * @param cpuId       preferred crafting CPU, or {@code null} for automatic selection
 * @param cooldown    minutes before the same rule may submit again
 * @param failures    consecutive failed submissions; the rule is disabled at {@link #MAX_FAILURES}
 * @param pausedUntil failure backoff, or {@code null}
 * @param lastError   error code of the last failed submission, or {@code null}
 */
public record RestockRule(UUID id, UUID networkId, UUID createdBy, OrderTarget resource, long minimum, long restockTo,
                          String cpuId, int cooldown, boolean enabled, Instant lastRunAt, UUID lastOrderId,
                          int failures, Instant pausedUntil, String lastError, Instant createdAt) {
    public static final int MAX_COOLDOWN_MINUTES = 7 * 24 * 60;
    public static final int MIN_COOLDOWN_MINUTES = 1;
    /** Consecutive failures after which a rule turns itself off instead of retrying forever. */
    public static final int MAX_FAILURES = 5;

    public RestockRule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** Whether the rule may submit at {@code now}: not cooling down and not in failure backoff. */
    public boolean ready(Instant now) {
        if (pausedUntil != null && now.isBefore(pausedUntil)) {
            return false;
        }
        return lastRunAt == null || !now.isBefore(lastRunAt.plusSeconds(cooldown * 60L));
    }

    public RestockRule submitted(Instant now, UUID orderId) {
        return new RestockRule(id, networkId, createdBy, resource, minimum, restockTo, cpuId, cooldown, enabled, now,
                orderId, 0, null, null, createdAt);
    }

    /** Records a failed submission; the rule disables itself once it has failed {@link #MAX_FAILURES} times. */
    public RestockRule failed(Instant now, String errorCode) {
        int count = failures + 1;
        return new RestockRule(id, networkId, createdBy, resource, minimum, restockTo, cpuId, cooldown,
                count < MAX_FAILURES, now, lastOrderId, count, now.plus(backoffFor(count)), errorCode, createdAt);
    }

    /** How long to wait after {@code count} failed submissions: the cooldown, doubled per failure, capped at a day. */
    private Duration backoffFor(int count) {
        long minutes = cooldown * (1L << Math.min(count, 10));
        return Duration.ofMinutes(Math.min(minutes, Duration.ofDays(1).toMinutes()));
    }
}
