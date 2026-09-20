package io.github.codaaaaaa.mecc.core.automation;

import io.github.codaaaaaa.mecc.core.auth.Session;
import io.github.codaaaaaa.mecc.core.automation.RestockViews.RequesterList;
import io.github.codaaaaaa.mecc.core.automation.RestockViews.RuleList;
import io.github.codaaaaaa.mecc.core.automation.RestockViews.RuleView;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Keep Stock / Auto Restock rules (spec section 25). Viewing needs {@code VIEW_NETWORK}; changing a rule needs
 * {@code MANAGE_AUTOMATION}, because rules spend the network's resources. Every change is audited.
 */
public interface RestockService {

    /**
     * @param resourceId text form of the resource; ignored by {@link #updateRule}, a rule keeps its resource
     * @param cpuId      preferred CPU, or {@code null}/blank for automatic
     */
    record RuleInput(String resourceId, Long minimum, Long restockTo, String cpuId, Integer cooldownMinutes,
                     Boolean enabled) {
    }

    CompletionStage<RuleList> rules(Session session, UUID networkId, String locale);

    /**
     * Fails with {@code VALIDATION_FAILED}, {@code RESOURCE_NOT_FOUND} when the network neither stores nor can craft
     * the resource, or {@code CONFLICT} beyond the per-network limit or for a resource that already has a rule.
     */
    CompletionStage<RuleView> createRule(Session session, UUID networkId, RuleInput input, String locale);

    /** Changes minimum, target, CPU, cooldown, and enabled; a {@code null} field is left as it is. */
    CompletionStage<RuleView> updateRule(Session session, UUID networkId, UUID ruleId, RuleInput input, String locale);

    CompletionStage<Void> deleteRule(Session session, UUID networkId, UUID ruleId);

    /** Kill switch (spec section 25): turns every rule of the network off at once. */
    CompletionStage<RuleList> stopAll(Session session, UUID networkId, String locale);

    /**
     * The in-game ME Requesters of the network (the optional ME Requester mod): the same keep-in-stock job as the
     * rules above, configured on a block. Needs {@code VIEW_NETWORK}; an offline network or a server without the
     * mod reports nothing instead of failing.
     */
    CompletionStage<RequesterList> requesters(Session session, UUID networkId, String locale);

    /**
     * Empties one request slot of an ME Requester, as taking its request out in game would. Needs
     * {@code MANAGE_AUTOMATION} and is audited; fails with {@code NOT_FOUND} when that requester or slot is gone.
     */
    CompletionStage<Void> deleteRequest(Session session, UUID networkId, String requesterId, int slot);
}
