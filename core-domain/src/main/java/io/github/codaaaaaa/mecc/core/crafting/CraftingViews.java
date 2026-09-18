package io.github.codaaaaaa.mecc.core.crafting;

import io.github.codaaaaaa.mecc.core.auth.AuthViews.UserView;
import io.github.codaaaaaa.mecc.core.networks.BlockLocation;
import io.github.codaaaaaa.mecc.core.resources.ResourceViews.ResourceLabel;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** API response models for crafting plans, orders, and CPUs (spec sections 9-12). */
public final class CraftingViews {
    private CraftingViews() {
    }

    /** How far a progress value can be trusted (spec section 11). */
    public enum Confidence {
        /** Reported by the crafting system itself. */
        AUTHORITATIVE,
        /** Derived from partial information. */
        ESTIMATED,
        /** Nothing meaningful is known: show activity, not a percentage. */
        NONE
    }

    /**
     * Normalized progress. Never a fabricated percentage: {@code percent} is {@code null} when there is no
     * meaningful denominator.
     *
     * @param completed final outputs delivered so far, or {@code null} when unknown
     * @param remaining final outputs still to deliver, or {@code null} when unknown
     * @param requested final outputs requested
     * @param percent   0-100, or {@code null}
     */
    public record ProgressView(Long completed, Long remaining, long requested, Double percent, Confidence confidence) {

        public static ProgressView unknown(long requested) {
            return new ProgressView(null, null, requested, null, Confidence.NONE);
        }

        public static ProgressView finished(long requested) {
            return new ProgressView(requested, 0L, requested, 100.0, Confidence.AUTHORITATIVE);
        }
    }

    /**
     * The job a CPU is running.
     *
     * @param jobId       platform job identifier, or {@code null} when the CPU type does not expose one
     * @param amount      requested amount of {@code output}
     * @param elapsedMillis time the job has been running, or {@code null}
     * @param orderId     the ME Control Center order behind the job, or {@code null} (e.g. started in game)
     * @param initiator   who started it through ME Control Center, or {@code null}
     * @param cancellable whether the caller may cancel it
     */
    public record CpuJobView(
            String jobId,
            ResourceLabel output,
            long amount,
            ProgressView progress,
            Long elapsedMillis,
            UUID orderId,
            UserView initiator,
            boolean cancellable) {
    }

    /**
     * One crafting CPU (spec section 12). Boxed fields are optional capabilities: {@code null} means the
     * platform cannot report them.
     *
     * @param id            stable while the CPU exists; opaque
     * @param name          player-given name, or {@code null}
     * @param location      where the CPU is, or {@code null}
     * @param online        powered and connected, or {@code null} when unknown
     * @param storageBytes  crafting storage
     * @param selectionMode which requests may use it automatically: {@code ANY}, {@code PLAYER_ONLY}, {@code MACHINE_ONLY}
     */
    public record CpuView(
            String id,
            String name,
            BlockLocation location,
            boolean busy,
            Boolean online,
            long storageBytes,
            int coProcessors,
            String selectionMode,
            CpuJobView job) {
    }

    /** @param assetVersion part of icon URLs, as in resource pages */
    public record CpuList(Instant capturedAt, List<CpuView> cpus, String assetVersion) {
    }

    /**
     * One line of a crafting plan.
     *
     * @param stored  taken from storage
     * @param toCraft crafted as part of the job
     * @param missing neither stored nor craftable: the job cannot start
     */
    public record PlanEntryView(ResourceLabel resource, long stored, long toCraft, long missing) {
    }

    public enum PlanState {
        CALCULATING,
        READY,
        FAILED
    }

    /** Why a CPU cannot take a plan. */
    public enum CpuUnsuitableReason {
        BUSY,
        OFFLINE,
        TOO_SMALL,
        /** Its selection mode excludes requests made by players. */
        EXCLUDED
    }

    /** @param reason {@code null} when the CPU can run the plan */
    public record CpuCandidateView(
            String id,
            String name,
            boolean busy,
            Boolean online,
            long storageBytes,
            int coProcessors,
            String selectionMode,
            CpuUnsuitableReason reason) {
    }

    /**
     * A crafting plan awaiting confirmation (spec section 9.2). Fields other than the identity are
     * {@code null} while {@code state} is {@code CALCULATING}.
     *
     * @param amount       amount the plan crafts
     * @param complete     {@code false} when ingredients are missing and the plan cannot be started
     * @param bytes        crafting storage the job needs
     * @param entries      ingredients and intermediates, missing first; at most a bounded number
     * @param totalEntries entries before truncation
     * @param cpus         CPUs and whether each can run the plan
     * @param expiresAt    after this the plan must be recalculated
     */
    public record PlanView(
            String id,
            PlanState state,
            UUID networkId,
            ResourceLabel output,
            long requestedAmount,
            Long amount,
            Boolean complete,
            Long bytes,
            boolean multiplePaths,
            List<PlanEntryView> entries,
            int totalEntries,
            List<CpuCandidateView> cpus,
            String errorCode,
            String errorMessage,
            Instant createdAt,
            Instant expiresAt,
            String assetVersion) {
    }

    public record CpuRef(String id, String name) {
    }

    public record FailureView(String code, String message) {
    }

    /**
     * A crafting order (spec section 10).
     *
     * @param progress      live progress for running orders, the final value otherwise; {@code null} when unknown
     * @param elapsedMillis how long the job has been (or was) running, or {@code null}
     * @param observed      for running orders: whether the job is currently visible on a loaded CPU
     * @param cancellable   whether the caller may cancel it now
     */
    public record OrderView(
            UUID id,
            UUID networkId,
            UserView creator,
            ResourceLabel target,
            long amount,
            OrderState state,
            OrderSource source,
            Instant createdAt,
            Instant startedAt,
            Instant endedAt,
            CpuRef cpu,
            ProgressView progress,
            Long elapsedMillis,
            FailureView failure,
            boolean cancellable,
            boolean observed,
            Instant lastObservedAt) {
    }

    /** @param nextCursor pass as {@code before} for the next page, or {@code null} at the end */
    public record OrderPage(List<OrderView> orders, String nextCursor, String assetVersion) {
    }

    /** A saved crafting preset (spec section 24). {@code amount} is raw, like order amounts. */
    public record SavedOrderView(UUID id, UUID networkId, String name, ResourceLabel target, long amount, String cpuId,
                                 String notes, Instant createdAt, Instant updatedAt) {
    }

    public record SavedOrderList(List<SavedOrderView> orders, int limit, String assetVersion) {
    }

    /** @param actor player who caused the event, or {@code null} */
    public record OrderEventView(Instant at, OrderEventType type, UserView actor, Map<String, String> details) {
    }

    public record OrderDetailView(OrderView order, List<OrderEventView> events, String assetVersion) {
    }
}
