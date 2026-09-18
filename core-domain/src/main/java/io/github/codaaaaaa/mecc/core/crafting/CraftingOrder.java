package io.github.codaaaaaa.mecc.core.crafting;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A persisted crafting order (spec sections 9.3 and 10). Created before the job is handed to the crafting
 * system, so every request stays traceable even if the browser disconnects.
 *
 * @param deviceId        device that submitted the order, or {@code null}
 * @param jobId           the platform's identifier of the running job, or {@code null} when it has none
 * @param cpuId           crafting CPU running the job, or {@code null}
 * @param cpuName         that CPU's display name at the time, or {@code null}
 * @param planBytes       crafting storage the job needs, or {@code null}
 * @param startedAt       when a CPU accepted the job
 * @param endedAt         when the order reached a final state
 * @param lastObservedAt  last time the running job was seen on a CPU
 * @param progressPercent last known progress, or {@code null}
 * @param failureCode     machine-readable reason for {@code FAILED}/{@code UNKNOWN}
 * @param failureMessage  human-readable reason
 */
public record CraftingOrder(
        UUID id,
        UUID networkId,
        UUID creatorUuid,
        String deviceId,
        OrderSource source,
        OrderTarget target,
        long amount,
        OrderState state,
        String jobId,
        String cpuId,
        String cpuName,
        Long planBytes,
        Instant createdAt,
        Instant startedAt,
        Instant endedAt,
        Instant lastObservedAt,
        Double progressPercent,
        String failureCode,
        String failureMessage) {

    public CraftingOrder {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(creatorUuid, "creatorUuid");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public CraftingOrder started(String newJobId, String newCpuId, String newCpuName, Instant at) {
        return new CraftingOrder(id, networkId, creatorUuid, deviceId, source, target, amount, OrderState.RUNNING,
                newJobId, newCpuId, newCpuName, planBytes, createdAt, at, null, at, 0.0, null, null);
    }

    /** The running job was seen again, possibly on a rebuilt CPU. */
    public CraftingOrder observed(String newJobId, String newCpuId, String newCpuName, Double progress, Instant at) {
        return new CraftingOrder(id, networkId, creatorUuid, deviceId, source, target, amount, state, newJobId,
                newCpuId, newCpuName, planBytes, createdAt, startedAt, endedAt, at, progress, failureCode, failureMessage);
    }

    /** A final state. {@code code}/{@code message} explain {@code FAILED} and {@code UNKNOWN}. */
    public CraftingOrder ended(OrderState finalState, Instant at, String code, String message) {
        if (finalState.active()) {
            throw new IllegalArgumentException("Not a final state: " + finalState);
        }
        Double progress = finalState == OrderState.COMPLETED ? Double.valueOf(100.0) : progressPercent;
        return new CraftingOrder(id, networkId, creatorUuid, deviceId, source, target, amount, finalState, jobId,
                cpuId, cpuName, planBytes, createdAt, startedAt, at, lastObservedAt, progress, code, message);
    }
}
