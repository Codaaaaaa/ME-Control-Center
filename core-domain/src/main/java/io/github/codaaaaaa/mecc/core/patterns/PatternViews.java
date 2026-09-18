package io.github.codaaaaaa.mecc.core.patterns;

import io.github.codaaaaaa.mecc.core.auth.AuthViews.UserView;
import io.github.codaaaaaa.mecc.core.networks.BlockLocation;
import io.github.codaaaaaa.mecc.core.resources.ResourceViews.ResourceLabel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** API response models for Pattern Studio, pattern providers, and recipes (spec sections 13-17). */
public final class PatternViews {
    private PatternViews() {
    }

    /** A resource and amount inside a pattern. */
    public record PatternStackView(ResourceLabel resource, long amount) {
    }

    /**
     * A pattern definition with display labels. Empty input slots are {@code null}, exactly as in
     * {@link PatternDefinition}.
     */
    public record DefinitionView(
            PatternType type,
            List<PatternStackView> inputs,
            List<PatternStackView> outputs,
            boolean substitutes,
            boolean fluidSubstitutes,
            String recipeId) {
    }

    public record DraftView(
            UUID id,
            String name,
            String description,
            UUID networkId,
            DefinitionView definition,
            Instant createdAt,
            Instant updatedAt,
            String assetVersion) {
    }

    /** @param max drafts one user may keep */
    public record DraftList(List<DraftView> drafts, int max, String assetVersion) {
    }

    /**
     * Result of checking a definition against the network's game state. Nothing is changed.
     *
     * @param outputs       what the pattern produces: from the recipe for recipe-driven types, as authored otherwise
     * @param recipeId      the game recipe the pattern would use, or {@code null}
     * @param blankPatterns Blank Patterns in the network's storage (encoding consumes one), or {@code null} when
     *                      the definition failed before the network was consulted
     */
    public record ValidationView(
            boolean valid,
            List<PatternIssue> issues,
            List<PatternStackView> outputs,
            String recipeId,
            Long blankPatterns,
            String assetVersion) {
    }

    /**
     * An encoded pattern stored in a provider.
     *
     * @param type    {@code null} when the pattern comes from an addon ME Control Center does not know
     * @param inputs  what one run consumes (first alternative of each input)
     */
    public record StoredPatternView(int slot, PatternType type, List<PatternStackView> outputs, List<PatternStackView> inputs) {
    }

    /**
     * A pattern provider or other pattern container, e.g. a multiblock pattern buffer (spec section 15). Boxed fields
     * are {@code null} when the container type does not report them.
     *
     * @param id         opaque, stable while the provider stays in place
     * @param name       what the Pattern Access Terminal shows: the custom name, else the machine it serves
     * @param icon       the machine or provider, or {@code null}
     * @param kind       the container itself, e.g. Pattern Provider or ME Pattern Buffer, or {@code null}
     * @param machine    the machine it supplies, e.g. the multiblock of a pattern buffer, or {@code null}
     * @param customName the player-given name, or {@code null}
     * @param renamable  whether its name can be changed from ME Control Center
     * @param online     powered and has a channel
     * @param lockMode   AE2 lock-crafting mode, e.g. {@code NONE}, {@code LOCK_UNTIL_PULSE}
     */
    public record ProviderView(
            String id,
            String name,
            ResourceLabel icon,
            ResourceLabel kind,
            ResourceLabel machine,
            String customName,
            boolean renamable,
            BlockLocation location,
            boolean online,
            int slots,
            int usedSlots,
            Integer priority,
            Boolean blocking,
            String lockMode,
            Boolean visibleInTerminal,
            List<StoredPatternView> patterns) {
    }

    /**
     * @param blankPatterns Blank Patterns in the network's storage
     * @param canDeploy     whether the caller may deploy patterns on this network
     * @param canConfigure  whether the caller may change provider settings such as names
     */
    public record ProviderList(Instant capturedAt, List<ProviderView> providers, long blankPatterns, boolean canDeploy,
                               boolean canConfigure, String assetVersion) {
    }

    /**
     * Outcome of a successful encode or deploy.
     *
     * @param providerId {@code null} when the pattern went into ME storage
     * @param slot       provider slot, or {@code null}
     */
    public record EncodeResultView(
            UUID deploymentId,
            PatternDeployment.Action action,
            List<PatternStackView> outputs,
            String providerId,
            String providerName,
            Integer slot,
            String assetVersion) {
    }

    /** @param output primary output, or {@code null} if unknown */
    public record DeploymentView(
            UUID id,
            Instant at,
            UserView actor,
            PatternDeployment.Action action,
            PatternType type,
            ResourceLabel output,
            String providerName,
            Integer slot,
            String errorCode) {
    }

    public record DeploymentList(List<DeploymentView> deployments, String assetVersion) {
    }

    /**
     * One slot of a recipe: the resources it accepts, most common first.
     *
     * @param amount raw amount the slot consumes
     * @param more   accepted resources beyond those listed
     */
    public record RecipeSlotView(List<ResourceLabel> options, long amount, int more) {
    }

    /** @param name localized machine or recipe type name, or {@code null} when only the id is known */
    public record RecipeCategoryView(String id, String name) {
    }

    /**
     * A game recipe usable to fill a pattern (spec section 13, "Fill from Recipe").
     *
     * @param slots      input slots in pattern order; {@code null} for empty crafting-grid cells
     * @param byproducts further guaranteed outputs of processing recipes
     * @param category   the machine or recipe type, or {@code null} for crafting-table-like recipes
     * @param complete   {@code false} when the machine may consume more than listed (e.g. fluids): check the pattern
     */
    public record RecipeView(String id, PatternType type, List<RecipeSlotView> slots, PatternStackView output,
                             List<PatternStackView> byproducts, RecipeCategoryView category, boolean complete) {
    }

    /** @param truncated more recipes matched than are listed */
    public record RecipeList(List<RecipeView> recipes, boolean truncated, String assetVersion) {
    }
}
