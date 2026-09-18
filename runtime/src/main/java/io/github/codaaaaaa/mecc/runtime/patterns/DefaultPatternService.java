package io.github.codaaaaaa.mecc.runtime.patterns;

import io.github.codaaaaaa.mecc.assets.ResourceNames;
import io.github.codaaaaaa.mecc.core.audit.AuditAction;
import io.github.codaaaaaa.mecc.core.audit.AuditEvent;
import io.github.codaaaaaa.mecc.core.audit.AuditEvent.AuditResult;
import io.github.codaaaaaa.mecc.core.auth.Session;
import io.github.codaaaaaa.mecc.core.config.PatternsConfig;
import io.github.codaaaaaa.mecc.core.crafting.OrderTarget;
import io.github.codaaaaaa.mecc.core.error.ErrorCode;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import io.github.codaaaaaa.mecc.core.live.LiveEvent;
import io.github.codaaaaaa.mecc.core.patterns.PatternDefinition;
import io.github.codaaaaaa.mecc.core.patterns.PatternDeployment;
import io.github.codaaaaaa.mecc.core.patterns.PatternDraft;
import io.github.codaaaaaa.mecc.core.patterns.PatternIssue;
import io.github.codaaaaaa.mecc.core.patterns.PatternRules;
import io.github.codaaaaaa.mecc.core.patterns.PatternService;
import io.github.codaaaaaa.mecc.core.patterns.PatternType;
import io.github.codaaaaaa.mecc.core.patterns.PatternViews.DeploymentList;
import io.github.codaaaaaa.mecc.core.patterns.PatternViews.DraftList;
import io.github.codaaaaaa.mecc.core.patterns.PatternViews.DraftView;
import io.github.codaaaaaa.mecc.core.patterns.PatternViews.EncodeResultView;
import io.github.codaaaaaa.mecc.core.patterns.PatternViews.ProviderList;
import io.github.codaaaaaa.mecc.core.patterns.PatternViews.RecipeList;
import io.github.codaaaaaa.mecc.core.patterns.PatternViews.RecipeView;
import io.github.codaaaaaa.mecc.core.patterns.PatternViews.ValidationView;
import io.github.codaaaaaa.mecc.core.permissions.NetworkAccess;
import io.github.codaaaaaa.mecc.core.permissions.NetworkCapability;
import io.github.codaaaaaa.mecc.core.persistence.DataStore;
import io.github.codaaaaaa.mecc.core.persistence.Repositories;
import io.github.codaaaaaa.mecc.core.resources.ResourceId;
import io.github.codaaaaaa.mecc.core.resources.ResourceQuery;
import io.github.codaaaaaa.mecc.core.resources.ResourceViews.ResourcePage;
import io.github.codaaaaaa.mecc.core.resources.ResourceViews.ResourceView;
import io.github.codaaaaaa.mecc.core.users.PlayerProfile;
import io.github.codaaaaaa.mecc.platform.PatternPlatform;
import io.github.codaaaaaa.mecc.platform.PatternPlatform.EncodeOutcome;
import io.github.codaaaaaa.mecc.platform.RecipePlatform.PatternRecipe;
import io.github.codaaaaaa.mecc.platform.thread.ServerThreadGateway;
import io.github.codaaaaaa.mecc.runtime.crafting.ResourceLabels;
import io.github.codaaaaaa.mecc.runtime.crafting.UserCache;
import io.github.codaaaaaa.mecc.runtime.networks.NetworkGuard;
import io.github.codaaaaaa.mecc.runtime.resources.DefaultResourceService;
import io.github.codaaaaaa.mecc.runtime.resources.ResourceCatalog;
import io.github.codaaaaaa.mecc.runtime.resources.TagIndex;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Pattern Studio (spec sections 13-16). Drafts live in the database; recipes and registered resources come from
 * {@link RecipeLibrary}; validation and encoding run on the server thread through the platform, which also
 * revalidates everything and consumes the Blank Pattern. Every encode and deploy attempt is kept as history and
 * audited.
 */
public final class DefaultPatternService implements PatternService {
    static final Duration SERVER_CALL_TIMEOUT = Duration.ofSeconds(5);
    static final int MAX_RECIPES = 50;
    static final int MAX_DEPLOYMENTS = 50;

    private final DataStore store;
    private final NetworkGuard guard;
    private final PatternPlatform patterns;
    private final ServerThreadGateway gateway;
    private final RecipeLibrary library;
    private final ProviderSnapshots providers;
    private final PatternPresenter presenter;
    private final ResourceLabels labels;
    private final TagIndex tags;
    private final Supplier<ResourceNames> names;
    private final Map<String, String> modNames;
    private final UserCache users;
    private final PatternsConfig config;
    private final PatternRules rules;
    private final Clock clock;
    /** Players with an encode in progress: a double click must never consume two Blank Patterns. */
    private final Set<UUID> encoding = ConcurrentHashMap.newKeySet();
    private volatile BiConsumer<UUID, LiveEvent> liveEvents = (network, event) -> {
    };

    public DefaultPatternService(DataStore store, NetworkGuard guard, PatternPlatform patterns, ServerThreadGateway gateway,
                                 RecipeLibrary library, ProviderSnapshots providers, PatternPresenter presenter,
                                 ResourceLabels labels, TagIndex tags, Supplier<ResourceNames> names,
                                 Map<String, String> modNames, UserCache users, PatternsConfig config, Clock clock) {
        this.store = store;
        this.guard = guard;
        this.patterns = patterns;
        this.gateway = gateway;
        this.library = library;
        this.providers = providers;
        this.presenter = presenter;
        this.labels = labels;
        this.tags = tags;
        this.names = names;
        this.modNames = Map.copyOf(modNames);
        this.users = users;
        this.config = config;
        this.rules = new PatternRules(config.maxPatternInputs(), config.maxPatternOutputs());
        this.clock = clock;
    }

    /** Receives network events caused by this service (for live updates). */
    public void onLiveEvent(BiConsumer<UUID, LiveEvent> listener) {
        this.liveEvents = listener;
    }

    // --- drafts ---------------------------------------------------------------------------------------

    @Override
    public CompletionStage<DraftList> drafts(Session session, String locale) {
        String language = locale(locale);
        return store.read(repos -> repos.patternDrafts().listByOwner(uuid(session)))
                .thenCombine(library.book(), (drafts, book) -> new DraftList(
                        drafts.stream().map(draft -> presenter.draft(draft, book, language)).toList(),
                        config.maxDraftsPerUser(), presenter.assetVersion()));
    }

    @Override
    public CompletionStage<DraftView> draft(Session session, UUID draftId, String locale) {
        return store.read(repos -> ownDraft(repos, session, draftId))
                .thenCombine(library.book(), (draft, book) -> presenter.draft(draft, book, locale(locale)));
    }

    @Override
    public CompletionStage<DraftView> createDraft(Session session, DraftInput input, String locale) {
        PatternDefinition definition = draftDefinition(input);
        String name = draftName(input.name());
        String description = draftDescription(input.description());
        return networkHint(session, input.networkId())
                .thenCompose(ignored -> store.write(repos -> {
                    if (repos.patternDrafts().countByOwner(uuid(session)) >= config.maxDraftsPerUser()) {
                        throw new MeccException(ErrorCode.CONFLICT, "You already have the maximum number of pattern drafts",
                                Map.of("max", config.maxDraftsPerUser()));
                    }
                    Instant now = clock.instant();
                    PatternDraft draft = new PatternDraft(UUID.randomUUID(), uuid(session), input.networkId(), name,
                            description, definition, now, now);
                    repos.patternDrafts().insert(draft);
                    return draft;
                }))
                .thenCombine(library.book(), (draft, book) -> presenter.draft(draft, book, locale(locale)));
    }

    @Override
    public CompletionStage<DraftView> updateDraft(Session session, UUID draftId, DraftInput input, String locale) {
        PatternDefinition definition = draftDefinition(input);
        String name = draftName(input.name());
        String description = draftDescription(input.description());
        return networkHint(session, input.networkId())
                .thenCompose(ignored -> store.write(repos -> {
                    PatternDraft existing = ownDraft(repos, session, draftId);
                    PatternDraft updated = new PatternDraft(existing.id(), existing.ownerUuid(), input.networkId(), name,
                            description, definition, existing.createdAt(), clock.instant());
                    repos.patternDrafts().update(updated);
                    return updated;
                }))
                .thenCombine(library.book(), (draft, book) -> presenter.draft(draft, book, locale(locale)));
    }

    @Override
    public CompletionStage<Void> deleteDraft(Session session, UUID draftId) {
        return store.write(repos -> {
            ownDraft(repos, session, draftId);
            repos.patternDrafts().delete(draftId);
            return null;
        });
    }

    private static PatternDraft ownDraft(Repositories repos, Session session, UUID draftId) {
        return repos.patternDrafts().find(draftId)
                .filter(draft -> draft.ownerUuid().equals(uuid(session)))
                .orElseThrow(() -> new MeccException(ErrorCode.DRAFT_NOT_FOUND, "No such pattern draft"));
    }

    /** A draft may reference only a network the caller can see. */
    private CompletableFuture<Void> networkHint(Session session, UUID networkId) {
        return networkId == null ? CompletableFuture.completedFuture(null)
                : guard.access(session, networkId).thenApply(access -> null);
    }

    /** Drafts may be incomplete, but never malformed: only the shape and limits are enforced. */
    private PatternDefinition draftDefinition(DraftInput input) {
        if (input.definition() == null) {
            throw MeccException.validation("definition", "definition is required");
        }
        PatternDefinition definition = PatternRules.normalize(input.definition());
        for (PatternIssue issue : rules.check(definition)) {
            if (issue.code().equals(PatternIssue.WRONG_SLOT_COUNT) || issue.code().equals(PatternIssue.TOO_MANY_INPUTS)
                    || issue.code().equals(PatternIssue.TOO_MANY_OUTPUTS)
                    || issue.code().equals(PatternIssue.AMOUNT_TOO_LARGE)
                    || issue.code().equals(PatternIssue.INVALID_RECIPE_ID)) {
                throw new MeccException(ErrorCode.VALIDATION_FAILED, issue.message(),
                        Map.of("field", issue.field() == null ? "definition" : "definition." + issue.field()));
            }
        }
        return definition;
    }

    private static String draftName(String name) {
        String stripped = name == null ? "" : name.strip();
        if (stripped.isEmpty() || stripped.length() > PatternDraft.MAX_NAME_LENGTH || stripped.chars().anyMatch(Character::isISOControl)) {
            throw MeccException.validation("name", "The name must be 1-" + PatternDraft.MAX_NAME_LENGTH + " characters");
        }
        return stripped;
    }

    private static String draftDescription(String description) {
        String stripped = description == null ? "" : description.strip();
        if (stripped.length() > PatternDraft.MAX_DESCRIPTION_LENGTH) {
            throw MeccException.validation("description",
                    "The description must be at most " + PatternDraft.MAX_DESCRIPTION_LENGTH + " characters");
        }
        return stripped;
    }

    // --- game data ------------------------------------------------------------------------------------

    @Override
    public CompletionStage<ResourcePage> catalog(Session session, ResourceQuery query) {
        String locale = locale(query.locale());
        return library.book().thenCombine(tags.tags(), (book, currentTags) -> {
            ResourceCatalog catalog = book.catalog(locale, currentTags, names, modNames);
            int[] matches = catalog.query(query.search(), query.type(), query.sort(), query.descending());
            int from = Math.min(query.offset(), matches.length);
            int to = Math.min(matches.length, from + query.limit());
            List<ResourceView> entries = new ArrayList<>(to - from);
            for (int i = from; i < to; i++) {
                entries.add(catalog.view(matches[i]));
            }
            return new ResourcePage(book.snapshotId(), catalog.index().capturedAt(), matches.length, from, entries,
                    presenter.assetVersion());
        });
    }

    @Override
    public CompletionStage<RecipeList> recipes(Session session, PatternType type, String output, String input, String locale) {
        if (type == null) {
            return CompletableFuture.failedFuture(MeccException.validation("type", "type is required"));
        }
        boolean byOutput = output != null && !output.isBlank();
        boolean byInput = input != null && !input.isBlank();
        if (byOutput == byInput || (byInput && type != PatternType.STONECUTTING)) {
            return CompletableFuture.failedFuture(MeccException.validation(byOutput ? "output" : "input",
                    "Give an output, or for stonecutting an input"));
        }
        String field = byOutput ? "output" : "input";
        ResourceId id = ResourceId.parse(byOutput ? output : input).orElse(null);
        if (id == null) {
            return CompletableFuture.failedFuture(MeccException.validation(field, field + " is not a valid resource ID"));
        }
        String language = locale(locale);
        return library.book().thenApply(book -> {
            List<PatternRecipe> found = byOutput ? book.producing(type, id) : book.stonecuttingFrom(id);
            List<RecipeView> views = found.stream().limit(MAX_RECIPES).map(recipe -> presenter.recipe(recipe, language)).toList();
            return new RecipeList(views, found.size() > views.size(), presenter.assetVersion());
        });
    }

    // --- providers ------------------------------------------------------------------------------------

    @Override
    public CompletionStage<ProviderList> providers(Session session, UUID networkId, String locale) {
        return guard.access(session, networkId).thenCompose(access -> {
            NetworkGuard.require(access, NetworkCapability.VIEW_NETWORK);
            String gridKey = guard.gridKey(networkId);
            return providers.latest(networkId, gridKey).thenApply(capture -> new ProviderList(capture.capturedAt(),
                    presenter.providers(capture.providers(), locale(locale)), capture.blankPatterns(),
                    access.allows(NetworkCapability.DEPLOY_PATTERNS), access.allows(NetworkCapability.PROVIDER_SETTINGS),
                    presenter.assetVersion()));
        });
    }

    @Override
    public CompletionStage<Void> renameProvider(Session session, UUID networkId, String providerId, String name) {
        String stripped = name == null ? "" : name.strip();
        if (stripped.length() > PatternDraft.MAX_NAME_LENGTH || stripped.chars().anyMatch(Character::isISOControl)) {
            return CompletableFuture.failedFuture(MeccException.validation("name",
                    "The name must be at most " + PatternDraft.MAX_NAME_LENGTH + " characters"));
        }
        return guard.access(session, networkId).thenCompose(access -> {
            NetworkGuard.require(access, NetworkCapability.PROVIDER_SETTINGS);
            String gridKey = guard.gridKey(networkId);
            return gateway.call("patterns.rename", () -> patterns.rename(gridKey, providerId, stripped), SERVER_CALL_TIMEOUT)
                    .thenCompose(outcome -> {
                        if (outcome == PatternPlatform.RenameOutcome.NOT_FOUND) {
                            throw new MeccException(ErrorCode.PROVIDER_NOT_FOUND,
                                    "No such pattern provider on this network right now");
                        }
                        if (outcome == PatternPlatform.RenameOutcome.NOT_RENAMABLE) {
                            throw new MeccException(ErrorCode.PROVIDER_NOT_RENAMABLE, "This pattern container cannot be renamed");
                        }
                        providers.invalidate(networkId);
                        return store.write(repos -> {
                            repos.audit().append(new AuditEvent(clock.instant(), uuid(session), session.device().id(),
                                    networkId, AuditAction.PROVIDER_SETTING_CHANGE, "provider:" + providerId,
                                    AuditResult.SUCCESS, access.requiresOverride(NetworkCapability.PROVIDER_SETTINGS),
                                    Map.of("name", stripped)));
                            return (Void) null;
                        });
                    });
        });
    }

    // --- validation and encoding ------------------------------------------------------------------------

    @Override
    public CompletionStage<ValidationView> validate(Session session, UUID networkId, PatternDefinition definition,
                                                    String locale) {
        if (definition == null) {
            return CompletableFuture.failedFuture(MeccException.validation("definition", "definition is required"));
        }
        PatternDefinition normalized = PatternRules.normalize(definition);
        String language = locale(locale);
        return guard.liveGrid(session, networkId, NetworkCapability.PATTERN_STUDIO).thenCompose(gridKey -> {
            List<PatternIssue> issues = rules.check(normalized);
            if (!issues.isEmpty()) {
                return CompletableFuture.completedFuture(new ValidationView(false, issues, List.of(), null, null,
                        presenter.assetVersion()));
            }
            return gateway.call("patterns.check", () -> patterns.check(gridKey, normalized), SERVER_CALL_TIMEOUT)
                    .thenApply(check -> new ValidationView(check.issues().isEmpty(), check.issues(),
                            presenter.stacks(check.outputs(), language), check.recipeId(), check.blankPatterns(),
                            presenter.assetVersion()));
        });
    }

    @Override
    public CompletionStage<EncodeResultView> encode(Session session, UUID networkId, PatternDefinition definition, UUID draftId,
                                                    String providerId, String locale) {
        if (definition == null) {
            return CompletableFuture.failedFuture(MeccException.validation("definition", "definition is required"));
        }
        PatternDefinition normalized = PatternRules.normalize(definition);
        NetworkCapability capability = providerId == null ? NetworkCapability.PATTERN_STUDIO : NetworkCapability.DEPLOY_PATTERNS;
        PatternDeployment.Action action = providerId == null ? PatternDeployment.Action.ENCODE : PatternDeployment.Action.DEPLOY;
        String language = locale(locale);
        return guard.access(session, networkId).thenCompose(access -> {
            NetworkGuard.require(access, NetworkCapability.PATTERN_STUDIO);
            NetworkGuard.require(access, capability);
            String gridKey = guard.gridKey(networkId);
            List<PatternIssue> issues = rules.check(normalized);
            if (!issues.isEmpty()) {
                throw invalid(issues);
            }
            UUID player = uuid(session);
            if (!encoding.add(player)) {
                throw new MeccException(ErrorCode.CONFLICT, "Another pattern of yours is being encoded right now");
            }
            PlayerProfile actor = new PlayerProfile(player, session.user().playerName());
            CompletableFuture<EncodeOutcome> call;
            try {
                call = gateway.call("patterns.encode", () -> patterns.encode(gridKey, normalized, providerId, actor),
                        SERVER_CALL_TIMEOUT);
            } catch (RuntimeException e) {
                encoding.remove(player);
                throw e;
            }
            return call.whenComplete((outcome, error) -> encoding.remove(player))
                    .thenCompose(outcome -> record(session, access, networkId, normalized, draftId, action, capability,
                            outcome, language));
        });
    }

    /** Keeps the attempt as history and in the audit log, then answers or fails with the platform's reason. */
    private CompletableFuture<EncodeResultView> record(Session session, NetworkAccess access, UUID networkId,
                                                       PatternDefinition definition, UUID draftId,
                                                       PatternDeployment.Action action, NetworkCapability capability,
                                                       EncodeOutcome outcome, String locale) {
        OrderTarget output = outcome.outputs().isEmpty() ? null : labels.target(outcome.outputs().get(0).resource());
        String providerName = outcome.providerName() == null ? null : labels.text(outcome.providerName(), "en_us");
        PatternDeployment deployment = new PatternDeployment(UUID.randomUUID(), networkId, uuid(session),
                session.device().id(), draftId, definition.type(), action, output, outcome.providerId(), providerName,
                outcome.slot(), outcome.success() ? null : errorCode(outcome.errorCode()).name(), clock.instant());
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("type", definition.type().name());
        if (output != null) {
            parameters.put("output", output.resourceId().toString());
        }
        if (outcome.providerId() != null) {
            parameters.put("provider", outcome.providerId());
        }
        if (!outcome.success()) {
            parameters.put("error", deployment.errorCode());
        }
        return store.write(repos -> {
            repos.patternDeployments().insert(deployment);
            repos.audit().append(new AuditEvent(clock.instant(), uuid(session), session.device().id(), networkId,
                    action == PatternDeployment.Action.DEPLOY ? AuditAction.PATTERN_DEPLOY : AuditAction.PATTERN_ENCODE,
                    outcome.providerId() != null ? "provider:" + outcome.providerId() : "storage",
                    outcome.success() ? AuditResult.SUCCESS : AuditResult.FAILED,
                    outcome.success() && access.requiresOverride(capability), parameters));
            return deployment;
        }).thenApply(stored -> {
            if (!outcome.success()) {
                throw new CompletionException(failure(outcome, stored.id()));
            }
            providers.invalidate(networkId);
            liveEvents.accept(networkId, new LiveEvent(LiveEvent.PATTERN_DEPLOYED, clock.instant(), networkId,
                    deployedPayload(stored)));
            return new EncodeResultView(stored.id(), action, presenter.stacks(outcome.outputs(), locale),
                    outcome.providerId(), outcome.providerName() == null ? null : labels.text(outcome.providerName(), locale),
                    outcome.slot(), presenter.assetVersion());
        });
    }

    private static Map<String, Object> deployedPayload(PatternDeployment deployment) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("deploymentId", deployment.id().toString());
        payload.put("action", deployment.action().name());
        payload.put("providerId", deployment.providerId());
        return payload;
    }

    private static MeccException invalid(List<PatternIssue> issues) {
        return new MeccException(ErrorCode.PATTERN_INVALID, "This pattern cannot be encoded: " + issues.get(0).message(),
                Map.of("issues", issueDetails(issues)));
    }

    private static List<Map<String, String>> issueDetails(List<PatternIssue> issues) {
        return issues.stream().map(issue -> {
            Map<String, String> detail = new LinkedHashMap<>();
            detail.put("code", issue.code());
            if (issue.field() != null) {
                detail.put("field", issue.field());
            }
            detail.put("message", issue.message());
            return detail;
        }).collect(Collectors.toList());
    }

    private static MeccException failure(EncodeOutcome outcome, UUID deploymentId) {
        ErrorCode code = errorCode(outcome.errorCode());
        Map<String, Object> details = new HashMap<>(outcome.details());
        details.put("deploymentId", deploymentId.toString());
        if (!outcome.issues().isEmpty()) {
            details.put("issues", issueDetails(outcome.issues()));
        }
        return new MeccException(code, failureMessage(code), details);
    }

    private static ErrorCode errorCode(String code) {
        try {
            return code == null ? ErrorCode.ENCODE_FAILED : ErrorCode.valueOf(code);
        } catch (IllegalArgumentException e) {
            return ErrorCode.ENCODE_FAILED;
        }
    }

    static String failureMessage(ErrorCode code) {
        return switch (code) {
            case PATTERN_INVALID -> "This pattern cannot be encoded. Validate it to see why.";
            case UNSUPPORTED_RESOURCE_TYPE -> "The pattern uses a resource type that cannot be put into patterns here.";
            case NO_BLANK_PATTERN -> "The network's storage has no Blank Pattern. Nothing was encoded.";
            case NETWORK_NO_POWER -> "The network has too little stored energy. Nothing was encoded.";
            case PROVIDER_NOT_FOUND -> "The selected pattern provider is no longer part of the network.";
            case PROVIDER_OFFLINE -> "The selected pattern provider is offline (no power or no channel).";
            case PROVIDER_FULL -> "The selected pattern provider has no free pattern slot.";
            case DEPLOY_FAILED -> "The pattern provider did not accept the pattern. The Blank Pattern was returned.";
            case VERIFY_FAILED -> "The pattern provider did not hold the pattern afterwards. The Blank Pattern was returned.";
            default -> "The pattern could not be encoded. The Blank Pattern was returned.";
        };
    }

    // --- history --------------------------------------------------------------------------------------

    @Override
    public CompletionStage<DeploymentList> deployments(Session session, UUID networkId, String locale) {
        String language = locale(locale);
        return guard.access(session, networkId).thenCompose(access -> {
            NetworkGuard.require(access, NetworkCapability.VIEW_NETWORK);
            return store.read(repos -> repos.patternDeployments().recent(networkId, MAX_DEPLOYMENTS))
                    .thenCompose(found -> users.views(found.stream().map(PatternDeployment::actorUuid).collect(Collectors.toSet()))
                            .thenApply(names -> new DeploymentList(
                                    found.stream().map(deployment -> presenter.deployment(deployment, names, language)).toList(),
                                    presenter.assetVersion())));
        });
    }

    // --- helpers --------------------------------------------------------------------------------------

    private static String locale(String requested) {
        return requested != null && DefaultResourceService.LOCALES.contains(requested) ? requested : ResourceNames.FALLBACK_LOCALE;
    }

    private static UUID uuid(Session session) {
        return session.user().playerUuid();
    }
}
