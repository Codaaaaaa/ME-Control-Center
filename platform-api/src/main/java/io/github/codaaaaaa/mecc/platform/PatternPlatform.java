package io.github.codaaaaaa.mecc.platform;

import io.github.codaaaaaa.mecc.core.networks.BlockLocation;
import io.github.codaaaaaa.mecc.core.patterns.PatternDefinition;
import io.github.codaaaaaa.mecc.core.patterns.PatternIssue;
import io.github.codaaaaaa.mecc.core.patterns.PatternType;
import io.github.codaaaaaa.mecc.core.patterns.ProviderSettings;
import io.github.codaaaaaa.mecc.core.resources.ResourceDescriptor;
import io.github.codaaaaaa.mecc.core.resources.ResourceText;
import io.github.codaaaaaa.mecc.core.users.PlayerProfile;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pattern encoding and pattern providers (spec sections 13-15 and 43, {@code PatternPlatform}).
 *
 * <p>Definitions reaching this interface have passed {@link io.github.codaaaaaa.mecc.core.patterns.PatternRules};
 * the platform checks what only the game knows: that every resource exists, and that a recipe matches.
 * Provider identifiers are opaque, URL-safe, and stable while a provider stays in place.
 */
public interface PatternPlatform {

    /**
     * Copies every pattern provider of a grid with its patterns, plus the grid's Blank Pattern count.
     * Must not load chunks.
     *
     * @throws io.github.codaaaaaa.mecc.core.error.MeccException {@code NETWORK_OFFLINE} if the grid no longer exists
     */
    @ServerThreadOnly
    ProviderCapture captureProviders(String gridKey);

    /** Checks a definition against the game without changing anything. */
    @ServerThreadOnly
    PatternCheck check(String gridKey, PatternDefinition definition);

    /**
     * Encodes a pattern in one step (spec section 14): checks the definition and the destination, takes exactly
     * one Blank Pattern from the grid's storage, encodes it, and delivers it to the provider (then verifies it
     * is there) or, without a provider, into the grid's storage. Any failure after the Blank Pattern was taken
     * returns it. Never throws for a refusal, which is reported in the outcome instead.
     *
     * @param providerId destination provider, or {@code null} for the grid's storage
     * @param actor      the player on whose behalf storage is accessed
     */
    @ServerThreadOnly
    EncodeOutcome encode(String gridKey, PatternDefinition definition, String providerId, PlayerProfile actor);

    /** @param amount raw amount, e.g. millibuckets for fluids */
    record PatternAmount(ResourceDescriptor resource, long amount) {
        public PatternAmount {
            Objects.requireNonNull(resource, "resource");
        }
    }

    /**
     * @param issues        empty when the pattern can be encoded
     * @param outputs       what the pattern produces, when known
     * @param recipeId      the recipe the pattern would use, or {@code null}
     * @param blankPatterns Blank Patterns in the grid's storage
     */
    record PatternCheck(List<PatternIssue> issues, List<PatternAmount> outputs, String recipeId, long blankPatterns) {
        public PatternCheck {
            issues = List.copyOf(issues);
            outputs = List.copyOf(outputs);
        }
    }

    /**
     * An encoded pattern in a provider slot.
     *
     * @param type {@code null} for pattern types ME Control Center does not know (addons)
     */
    record StoredPattern(int slot, PatternType type, List<PatternAmount> outputs, List<PatternAmount> inputs) {
        public StoredPattern {
            outputs = List.copyOf(outputs);
            inputs = List.copyOf(inputs);
        }
    }

    /**
     * Renames a pattern container, as an anvil-named block or the Pattern Access Terminal would. An empty name
     * removes the custom name.
     */
    @ServerThreadOnly
    ProviderChange rename(String gridKey, String providerId, String name);

    enum ProviderChange {
        CHANGED,
        NOT_FOUND,
        /** This container type has no such name or setting (e.g. another mod's pattern buffer). */
        NOT_SUPPORTED
    }

    /**
     * Changes a provider's settings as its in-game screen would (spec section 15). Each {@code null} field is left
     * as it is. Returns {@link ProviderChange#NOT_SUPPORTED} for containers without these settings (other mods'
     * pattern buffers).
     */
    @ServerThreadOnly
    ProviderChange configure(String gridKey, String providerId, ProviderSettings settings);

    /**
     * Anything that holds patterns for the network: AE2 pattern providers and the pattern buffers other mods add for
     * their machines (e.g. GregTech multiblock pattern buffers).
     *
     * @param name       what the Pattern Access Terminal shows for it, or {@code null}
     * @param icon       the machine or provider, or {@code null}
     * @param kind       the container itself, e.g. a Pattern Provider or an ME Pattern Buffer, or {@code null}
     * @param machine    the machine it supplies, e.g. the multiblock a pattern buffer belongs to, or {@code null}
     * @param customName the name a player gave it, or {@code null}
     * @param renamable  whether {@link #rename} works for it
     * @param location   the provider block, or {@code null} when unknown
     * @param online     powered and has a channel
     * @param priority   {@code null} when the provider type does not have one
     * @param lockMode   {@code null} when the provider type does not have one
     */
    record ProviderState(String id, ResourceText name, ResourceDescriptor icon, ResourceDescriptor kind,
                         ResourceDescriptor machine, String customName, boolean renamable, BlockLocation location,
                         boolean online, int slots, Integer priority, Boolean blocking, String lockMode,
                         Boolean visibleInTerminal, List<StoredPattern> patterns) {
        public ProviderState {
            Objects.requireNonNull(id, "id");
            patterns = List.copyOf(patterns);
        }

        public int usedSlots() {
            return patterns.size();
        }
    }

    record ProviderCapture(Instant capturedAt, List<ProviderState> providers, long blankPatterns) {
        public ProviderCapture {
            providers = List.copyOf(providers);
        }
    }

    /**
     * @param errorCode    {@code null} on success, else one of {@code PATTERN_INVALID}, {@code UNSUPPORTED_RESOURCE_TYPE},
     *                     {@code NO_BLANK_PATTERN}, {@code NETWORK_NO_POWER}, {@code PROVIDER_NOT_FOUND},
     *                     {@code PROVIDER_OFFLINE}, {@code PROVIDER_FULL}, {@code ENCODE_FAILED},
     *                     {@code DEPLOY_FAILED}, {@code VERIFY_FAILED}
     * @param issues       why the definition is invalid, for {@code PATTERN_INVALID}
     * @param outputs      what the pattern produces, when known (also on failure)
     * @param providerName destination provider name, or {@code null}
     * @param slot         provider slot the pattern went into, or {@code null}
     * @param details      extra facts about a failure, e.g. {@code blankReturned: false}
     */
    record EncodeOutcome(String errorCode, List<PatternIssue> issues, List<PatternAmount> outputs, String providerId,
                         ResourceText providerName, Integer slot, Map<String, Object> details) {
        public EncodeOutcome {
            issues = List.copyOf(issues);
            outputs = List.copyOf(outputs);
            details = details == null ? Map.of() : Map.copyOf(details);
        }

        public static EncodeOutcome encoded(List<PatternAmount> outputs, String providerId, ResourceText providerName,
                                            Integer slot) {
            return new EncodeOutcome(null, List.of(), outputs, providerId, providerName, slot, Map.of());
        }

        public static EncodeOutcome failed(String errorCode, List<PatternAmount> outputs, Map<String, Object> details) {
            return new EncodeOutcome(errorCode, List.of(), outputs, null, null, null, details);
        }

        public static EncodeOutcome invalid(List<PatternIssue> issues, List<PatternAmount> outputs) {
            return new EncodeOutcome("PATTERN_INVALID", issues, outputs, null, null, null, Map.of());
        }

        public boolean success() {
            return errorCode == null;
        }
    }
}
