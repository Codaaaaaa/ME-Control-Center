package io.github.codaaaaaa.mecc.runtime.patterns;

import io.github.codaaaaaa.mecc.core.auth.AuthViews.UserView;
import io.github.codaaaaaa.mecc.core.patterns.PatternDefinition;
import io.github.codaaaaaa.mecc.core.patterns.PatternDeployment;
import io.github.codaaaaaa.mecc.core.patterns.PatternDraft;
import io.github.codaaaaaa.mecc.core.patterns.PatternStack;
import io.github.codaaaaaa.mecc.core.patterns.PatternViews.DefinitionView;
import io.github.codaaaaaa.mecc.core.patterns.PatternViews.DeploymentView;
import io.github.codaaaaaa.mecc.core.patterns.PatternViews.DraftView;
import io.github.codaaaaaa.mecc.core.patterns.PatternViews.PatternStackView;
import io.github.codaaaaaa.mecc.core.patterns.PatternViews.RecipeCategoryView;
import io.github.codaaaaaa.mecc.core.patterns.PatternViews.ProviderView;
import io.github.codaaaaaa.mecc.core.patterns.PatternViews.RecipeSlotView;
import io.github.codaaaaaa.mecc.core.patterns.PatternViews.RecipeView;
import io.github.codaaaaaa.mecc.core.patterns.PatternViews.StoredPatternView;
import io.github.codaaaaaa.mecc.core.resources.ResourceDescriptor;
import io.github.codaaaaaa.mecc.core.resources.ResourceId;
import io.github.codaaaaaa.mecc.core.resources.ResourceViews.ResourceLabel;
import io.github.codaaaaaa.mecc.platform.PatternPlatform.PatternAmount;
import io.github.codaaaaaa.mecc.platform.PatternPlatform.ProviderState;
import io.github.codaaaaaa.mecc.platform.PatternPlatform.StoredPattern;
import io.github.codaaaaaa.mecc.platform.RecipePlatform.Category;
import io.github.codaaaaaa.mecc.platform.RecipePlatform.PatternRecipe;
import io.github.codaaaaaa.mecc.runtime.crafting.ResourceLabels;
import io.github.codaaaaaa.mecc.runtime.patterns.RecipeLibrary.Book;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Builds Pattern Studio API views: labels in the caller's language, stable ordering. Thread-safe. */
public final class PatternPresenter {
    /** Accepted items listed per recipe slot; the rest is counted. */
    static final int RECIPE_OPTIONS = 8;

    private final ResourceLabels labels;
    private final Supplier<String> assetVersion;

    public PatternPresenter(ResourceLabels labels, Supplier<String> assetVersion) {
        this.labels = labels;
        this.assetVersion = assetVersion;
    }

    public String assetVersion() {
        return assetVersion.get();
    }

    /**
     * How to show a resource named by ID only: as the game registers it, else with its ID and the icon a
     * resource of that ID would have (addon types and resources removed from the modpack).
     */
    public ResourceLabel label(ResourceId id, Book book, String locale) {
        ResourceDescriptor registered = book.registered(id).orElse(null);
        ResourceDescriptor descriptor = registered != null && registered.id().equals(id) ? registered
                : registered != null
                ? new ResourceDescriptor(id, registered.descriptionKey(), registered.name(), registered.modId(),
                        registered.unit(), registered.iconKey())
                : new ResourceDescriptor(id, null, null, id.namespace(), null, iconKey(id));
        return labels.label(descriptor, locale);
    }

    static String iconKey(ResourceId id) {
        String kind = id.type().equals("item") || id.type().equals("fluid") ? id.type() : "other";
        return kind + "/" + id.namespace() + "/" + id.path();
    }

    public PatternStackView stack(PatternAmount amount, String locale) {
        return new PatternStackView(labels.label(amount.resource(), locale), amount.amount());
    }

    public List<PatternStackView> stacks(Collection<PatternAmount> amounts, String locale) {
        return amounts.stream().map(amount -> stack(amount, locale)).toList();
    }

    public DefinitionView definition(PatternDefinition definition, Book book, String locale) {
        return new DefinitionView(definition.type(), slots(definition.inputs(), book, locale),
                slots(definition.outputs(), book, locale), definition.substitutes(), definition.fluidSubstitutes(),
                definition.recipeId());
    }

    /** Keeps empty slots as {@code null}, so the grid layout survives. */
    private List<PatternStackView> slots(List<PatternStack> stacks, Book book, String locale) {
        List<PatternStackView> views = new ArrayList<>(stacks.size());
        for (PatternStack stack : stacks) {
            views.add(stack == null ? null : new PatternStackView(label(stack.resource(), book, locale), stack.amount()));
        }
        return java.util.Collections.unmodifiableList(views);
    }

    public DraftView draft(PatternDraft draft, Book book, String locale) {
        return new DraftView(draft.id(), draft.name(), draft.description(), draft.networkId(),
                definition(draft.definition(), book, locale), draft.createdAt(), draft.updatedAt(), assetVersion());
    }

    public List<ProviderView> providers(List<ProviderState> providers, String locale) {
        List<ProviderView> views = new ArrayList<>(providers.size());
        for (ProviderState provider : providers) {
            views.add(provider(provider, locale));
        }
        views.sort(Comparator.comparing((ProviderView view) -> view.name() == null ? "" : view.name(),
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(view -> view.location() == null ? "" : view.location().key())
                .thenComparing(ProviderView::id));
        return views;
    }

    public ProviderView provider(ProviderState provider, String locale) {
        String name = provider.name() == null ? null : labels.text(provider.name(), locale);
        return new ProviderView(provider.id(), name, labelOrNull(provider.icon(), locale),
                labelOrNull(provider.kind(), locale), labelOrNull(provider.machine(), locale), provider.customName(),
                provider.renamable(), provider.location(), provider.online(), provider.slots(), provider.usedSlots(), provider.priority(),
                provider.blocking(), provider.lockMode(), provider.visibleInTerminal(),
                provider.patterns().stream().map(pattern -> stored(pattern, locale)).toList());
    }

    private ResourceLabel labelOrNull(ResourceDescriptor descriptor, String locale) {
        return descriptor == null ? null : labels.label(descriptor, locale);
    }

    private StoredPatternView stored(StoredPattern pattern, String locale) {
        return new StoredPatternView(pattern.slot(), pattern.type(), stacks(pattern.outputs(), locale),
                stacks(pattern.inputs(), locale));
    }

    public RecipeView recipe(PatternRecipe recipe, String locale) {
        List<RecipeSlotView> slots = new ArrayList<>(recipe.slots().size());
        for (int i = 0; i < recipe.slots().size(); i++) {
            List<ResourceDescriptor> options = recipe.slots().get(i);
            if (options.isEmpty()) {
                slots.add(null);
                continue;
            }
            List<ResourceLabel> shown = options.stream().limit(RECIPE_OPTIONS).map(option -> labels.label(option, locale)).toList();
            slots.add(new RecipeSlotView(shown, recipe.amounts().get(i), options.size() - shown.size()));
        }
        return new RecipeView(recipe.id(), recipe.type(), java.util.Collections.unmodifiableList(slots),
                new PatternStackView(labels.label(recipe.output(), locale), recipe.outputAmount()),
                recipe.byproducts().stream()
                        .map(byproduct -> new PatternStackView(labels.label(byproduct.resource(), locale), byproduct.amount()))
                        .toList(),
                category(recipe.category(), locale), recipe.complete());
    }

    /** The machine name in the caller's language; an untranslated key is no better than the id. */
    private RecipeCategoryView category(Category category, String locale) {
        if (category == null) {
            return null;
        }
        String name = category.name() == null ? null : labels.text(category.name(), locale);
        boolean untranslated = name == null || name.isBlank() || name.equals(category.name().key());
        return new RecipeCategoryView(category.id(), untranslated ? null : name);
    }

    public DeploymentView deployment(PatternDeployment deployment, Map<java.util.UUID, UserView> users, String locale) {
        UserView actor = users.getOrDefault(deployment.actorUuid(), new UserView(deployment.actorUuid(), null));
        return new DeploymentView(deployment.id(), deployment.at(), actor, deployment.action(), deployment.type(),
                deployment.output() == null ? null : labels.label(deployment.output(), locale), deployment.providerName(),
                deployment.slot(), deployment.errorCode());
    }
}
