package io.github.codaaaaaa.mecc.core.alerts;

import io.github.codaaaaaa.mecc.core.crafting.OrderTarget;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A player's alert rule on one network (spec sections 21 and 23).
 *
 * @param resource   watched resource, frozen like an order target; {@code null} when the rule has none
 * @param threshold  in the unit of {@link AlertType#threshold()}; {@code null} when the type has none
 * @param window     minutes compared by percentage-change rules, else {@code null}
 * @param cooldown   minimum time between two notifications of this rule, in minutes
 * @param notifiedAt when this rule last notified, or {@code null}
 */
public record AlertRule(UUID id, UUID playerUuid, UUID networkId, AlertType type, OrderTarget resource, Long threshold,
                        Integer window, int cooldown, boolean enabled, AlertState state, Instant notifiedAt,
                        Instant createdAt) {
    public static final int MAX_COOLDOWN_MINUTES = 7 * 24 * 60;
    /** Longest window of a percentage-change rule, and longest stall a stall rule waits for. */
    public static final int MAX_WINDOW_MINUTES = 24 * 60;

    public AlertRule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public AlertRule withState(AlertState newState, Instant newNotifiedAt) {
        return new AlertRule(id, playerUuid, networkId, type, resource, threshold, window, cooldown, enabled, newState,
                newNotifiedAt, createdAt);
    }

    /**
     * Where this condition rule goes when its condition {@code holds} (spec section 23: cooldowns and recovery state
     * against spam). A condition that starts holding within the cooldown is remembered as
     * {@link AlertState#SUPPRESSED} and notifies once the cooldown has passed, if it still holds then.
     */
    public AlertState next(boolean holds, Instant now) {
        if (!holds) {
            return AlertState.OK;
        }
        if (state == AlertState.FIRING) {
            return AlertState.FIRING;
        }
        boolean cooledDown = notifiedAt == null || !now.isBefore(notifiedAt.plusSeconds(cooldown * 60L));
        return cooledDown ? AlertState.FIRING : AlertState.SUPPRESSED;
    }
}
