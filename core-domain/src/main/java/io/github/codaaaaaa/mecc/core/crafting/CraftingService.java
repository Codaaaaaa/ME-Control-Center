package io.github.codaaaaaa.mecc.core.crafting;

import io.github.codaaaaaa.mecc.core.auth.Session;
import io.github.codaaaaaa.mecc.core.crafting.CraftingViews.CpuList;
import io.github.codaaaaaa.mecc.core.crafting.CraftingViews.OrderDetailView;
import io.github.codaaaaaa.mecc.core.crafting.CraftingViews.OrderPage;
import io.github.codaaaaaa.mecc.core.crafting.CraftingViews.OrderView;
import io.github.codaaaaaa.mecc.core.crafting.CraftingViews.PlanView;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Remote autocrafting (spec sections 9-12). Every method checks the caller's access to the network first:
 * {@code NETWORK_NOT_FOUND} for networks they may not know about, {@code PERMISSION_DENIED} when their role
 * is insufficient. Nothing here blocks the calling thread or touches game state outside the server thread.
 */
public interface CraftingService {

    /** Crafting CPUs of the network and what they are doing. Requires {@code VIEW_NETWORK}. */
    CompletionStage<CpuList> cpus(Session session, UUID networkId, String locale);

    /**
     * Cancels whatever job a CPU is running, e.g. one started in game. Requires {@code CANCEL_ANY_CRAFT},
     * or {@code CANCEL_OWN_CRAFT} when the job is the caller's own ME Control Center order.
     *
     * @param jobId the job the caller saw, so a job that started since is never cancelled by mistake;
     *              {@code null} for CPUs that do not expose job identifiers
     */
    CompletionStage<Void> cancelCpuJob(Session session, UUID networkId, String cpuId, String jobId);

    /**
     * Starts calculating a crafting plan; nothing is submitted (spec section 9.1). Requires {@code SUBMIT_CRAFT}.
     * Completes when the plan is ready or after a short wait with state {@code CALCULATING}; then poll
     * {@link #plan(Session, UUID, String, String)}.
     *
     * @param resourceId text form of the resource to craft
     */
    CompletionStage<PlanView> calculate(Session session, UUID networkId, String resourceId, long amount, String locale);

    /** A plan previously started by the caller. */
    CompletionStage<PlanView> plan(Session session, UUID networkId, String planId, String locale);

    /**
     * Submits a ready plan (spec section 9.3). The order is recorded before it is handed to the crafting
     * system; if the crafting system rejects it, the call fails and the order is kept as {@code FAILED}.
     *
     * @param cpuId CPU to use, or {@code null} to let the crafting system choose
     */
    CompletionStage<OrderView> submit(Session session, UUID networkId, String planId, String cpuId, String locale);

    /** Orders of the network, newest first. Requires {@code VIEW_NETWORK}. */
    CompletionStage<OrderPage> orders(Session session, UUID networkId, OrderFilter filter, int limit, String cursor,
                                      String locale);

    CompletionStage<OrderDetailView> order(Session session, UUID networkId, UUID orderId, String locale);

    /** Cancels a running order. Requires {@code CANCEL_OWN_CRAFT} for own orders, {@code CANCEL_ANY_CRAFT} otherwise. */
    CompletionStage<OrderView> cancel(Session session, UUID networkId, UUID orderId, String locale);
}
