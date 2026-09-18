package io.github.codaaaaaa.mecc.core.error;

/**
 * Stable, machine-readable error codes exposed through the public API.
 *
 * <p>Codes are part of the API contract: add new ones freely, never rename existing ones.
 */
public enum ErrorCode {
    BAD_REQUEST(400),
    /** A request field failed validation. {@code details.field} names it. */
    VALIDATION_FAILED(400),
    /** The pairing key is unknown, expired, or already used. Deliberately does not say which. */
    PAIRING_KEY_INVALID(400),
    /** No valid device token was presented, or the device was revoked. */
    UNAUTHENTICATED(401),
    /** The caller is authenticated but their role does not allow the action. */
    PERMISSION_DENIED(403),
    /** A state-changing browser request came from a foreign origin. */
    ORIGIN_REJECTED(403),
    NOT_FOUND(404),
    /** The network does not exist or the caller is not allowed to know about it. */
    NETWORK_NOT_FOUND(404),
    DEVICE_NOT_FOUND(404),
    /** The player is unknown to ME Control Center and not online. */
    PLAYER_NOT_FOUND(404),
    /** No unenrolled ME network with the given anchor is currently loaded. */
    NETWORK_CANDIDATE_NOT_FOUND(404),
    METHOD_NOT_ALLOWED(405),
    /** The resource is not in the network's storage snapshot. */
    RESOURCE_NOT_FOUND(404),
    /** No icon can be produced for this resource; show a placeholder. */
    ICON_NOT_FOUND(404),
    /** The crafting order does not exist on this network. */
    ORDER_NOT_FOUND(404),
    /** The crafting plan does not exist, belongs to someone else, or expired. Calculate again. */
    PLAN_NOT_FOUND(404),
    /** No crafting CPU with this ID is part of the network right now. */
    CPU_NOT_FOUND(404),
    /** The request conflicts with current state (e.g. the network was enrolled concurrently). */
    CONFLICT(409),
    /** The network is not loaded, so live data such as storage cannot be read. */
    NETWORK_OFFLINE(409),
    /** The network's identity is ambiguous (split/merged); live data is withheld until it resolves. */
    NETWORK_UNAVAILABLE(409),
    /** The network has no pattern that produces this resource. */
    NOT_CRAFTABLE(409),
    /** The crafting calculation failed or took too long. */
    CRAFT_CALCULATION_FAILED(409),
    /** The plan is still being calculated. */
    PLAN_NOT_READY(409),
    /** The plan lacks ingredients; it can be inspected but not started. {@code details} lists nothing secret. */
    PLAN_INCOMPLETE(409),
    /** The plan was calculated for a network state that no longer exists (e.g. the network reconnected). */
    PLAN_STALE(409),
    /** The network has no crafting CPU. */
    NO_CRAFTING_CPU(409),
    /** Every CPU is busy, offline, too small, or excluded. {@code details} counts each reason when known. */
    NO_SUITABLE_CPU(409),
    CPU_BUSY(409),
    CPU_OFFLINE(409),
    /** The selected CPU does not have enough crafting storage for the plan. */
    CPU_TOO_SMALL(409),
    /** An ingredient could not be taken from storage when the job started; the plan is out of date. */
    MISSING_INGREDIENTS(409),
    /** The order or CPU job is not running (anymore), so it cannot be cancelled. */
    NOT_RUNNING(409),
    /** The crafting system refused the job for a reason ME Control Center does not know. */
    CRAFT_REJECTED(409),
    PAYLOAD_TOO_LARGE(413),
    UNSUPPORTED_MEDIA_TYPE(415),
    RATE_LIMITED(429),
    INTERNAL_ERROR(500),
    /** The Minecraft server is not running or is shutting down. */
    SERVER_UNAVAILABLE(503),
    /** ME Control Center is still starting or failed to start a required component. */
    SERVICE_UNAVAILABLE(503),
    /** Too many operations are already queued for the Minecraft server thread. */
    GATEWAY_BUSY(503),
    /** A queued server-thread operation did not start before its deadline. It was not executed. */
    SERVER_THREAD_TIMEOUT(504);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
