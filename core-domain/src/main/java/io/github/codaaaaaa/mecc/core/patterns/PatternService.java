package io.github.codaaaaaa.mecc.core.patterns;

import io.github.codaaaaaa.mecc.core.auth.Session;
import io.github.codaaaaaa.mecc.core.patterns.PatternViews.DeploymentList;
import io.github.codaaaaaa.mecc.core.patterns.PatternViews.DraftList;
import io.github.codaaaaaa.mecc.core.patterns.PatternViews.DraftView;
import io.github.codaaaaaa.mecc.core.patterns.PatternViews.EncodeResultView;
import io.github.codaaaaaa.mecc.core.patterns.PatternViews.ProviderList;
import io.github.codaaaaaa.mecc.core.patterns.PatternViews.RecipeList;
import io.github.codaaaaaa.mecc.core.patterns.PatternViews.ValidationView;
import io.github.codaaaaaa.mecc.core.resources.ResourceQuery;
import io.github.codaaaaaa.mecc.core.resources.ResourceViews.ResourcePage;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Pattern Studio (spec sections 13-16): drafts, recipe lookup, validation, encoding, and deployment into
 * pattern providers. Network-specific methods check access first ({@code NETWORK_NOT_FOUND},
 * {@code PERMISSION_DENIED}); nothing here touches game state outside the server thread.
 */
public interface PatternService {

    /** What a draft is made of, as submitted by the browser. */
    record DraftInput(String name, String description, UUID networkId, PatternDefinition definition) {
    }

    // --- drafts: personal, not tied to network permissions ---------------------------------------------

    CompletionStage<DraftList> drafts(Session session, String locale);

    CompletionStage<DraftView> draft(Session session, UUID draftId, String locale);

    /**
     * Drafts may be incomplete; only their shape is checked. Fails with {@code VALIDATION_FAILED} for a bad name or
     * shape and {@code CONFLICT} beyond the per-user draft limit.
     */
    CompletionStage<DraftView> createDraft(Session session, DraftInput input, String locale);

    CompletionStage<DraftView> updateDraft(Session session, UUID draftId, DraftInput input, String locale);

    CompletionStage<Void> deleteDraft(Session session, UUID draftId);

    // --- game data -------------------------------------------------------------------------------------

    /** Every registered item and fluid, searchable like the terminal; for resources not in storage. */
    CompletionStage<ResourcePage> catalog(Session session, ResourceQuery query);

    /**
     * Recipes of a pattern type, found by what they make ({@code output}) or, for stonecutting, by what they
     * take ({@code input}). Both are resource ID texts; exactly one is required. Processing recipes are the
     * server's machine recipes (smelting, GregTech machines, other mods).
     */
    CompletionStage<RecipeList> recipes(Session session, PatternType type, String output, String input, String locale);

    // --- network ---------------------------------------------------------------------------------------

    /** Pattern providers of the network and their patterns. Requires {@code VIEW_NETWORK}. */
    CompletionStage<ProviderList> providers(Session session, UUID networkId, String locale);

    /**
     * Renames a pattern provider or pattern buffer; a blank name removes the custom name. Requires
     * {@code PROVIDER_SETTINGS}. Fails with {@code PROVIDER_NOT_FOUND} or {@code PROVIDER_NOT_RENAMABLE}.
     */
    CompletionStage<Void> renameProvider(Session session, UUID networkId, String providerId, String name);

    /** Checks a definition against the game without changing anything. Requires {@code PATTERN_STUDIO}. */
    CompletionStage<ValidationView> validate(Session session, UUID networkId, PatternDefinition definition, String locale);

    /**
     * Encodes a pattern, consuming one Blank Pattern from the network (spec section 14). With a provider it
     * goes there ({@code DEPLOY_PATTERNS}) and is verified; without, into the network's storage
     * ({@code PATTERN_STUDIO}). On failure nothing is consumed.
     *
     * @param draftId    draft the definition came from, for history, or {@code null}
     * @param providerId destination provider, or {@code null}
     */
    CompletionStage<EncodeResultView> encode(Session session, UUID networkId, PatternDefinition definition, UUID draftId,
                                             String providerId, String locale);

    /** Recent encode and deploy attempts on the network. Requires {@code VIEW_NETWORK}. */
    CompletionStage<DeploymentList> deployments(Session session, UUID networkId, String locale);
}
