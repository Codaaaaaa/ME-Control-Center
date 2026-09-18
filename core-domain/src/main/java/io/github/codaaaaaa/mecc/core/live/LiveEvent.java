package io.github.codaaaaaa.mecc.core.live;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Envelope of a live update pushed over {@code /ws/v1} (spec section 31). Live events only improve UX;
 * REST stays the source of truth after (re)connecting.
 *
 * @param type      e.g. {@code crafting.order.updated}
 * @param networkId network the event is about, or {@code null}
 * @param payload   an API view, serialized like REST responses
 */
public record LiveEvent(String type, Instant timestamp, UUID networkId, Object payload) {
    public static final String SESSION_READY = "session.ready";
    public static final String SUBSCRIBED = "subscribed";
    /** A subscription was dropped, e.g. because access was revoked. Payload: {@code {reason}}. */
    public static final String SUBSCRIPTION_ENDED = "subscription.ended";
    public static final String NETWORK_STATUS_CHANGED = "network.status.changed";
    public static final String CPU_UPDATED = "cpu.updated";
    public static final String ORDER_CREATED = "crafting.order.created";
    public static final String ORDER_UPDATED = "crafting.order.updated";
    public static final String ORDER_COMPLETED = "crafting.order.completed";
    public static final String ORDER_FAILED = "crafting.order.failed";
    public static final String ERROR = "error";
    public static final String PONG = "pong";

    public LiveEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(timestamp, "timestamp");
    }
}
