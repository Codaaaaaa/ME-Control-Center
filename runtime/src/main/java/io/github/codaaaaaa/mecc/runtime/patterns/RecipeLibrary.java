package io.github.codaaaaaa.mecc.runtime.patterns;

import io.github.codaaaaaa.mecc.assets.ResourceNames;
import io.github.codaaaaaa.mecc.core.patterns.PatternType;
import io.github.codaaaaaa.mecc.core.resources.ResourceDescriptor;
import io.github.codaaaaaa.mecc.core.resources.ResourceId;
import io.github.codaaaaaa.mecc.core.resources.ResourceIndex;
import io.github.codaaaaaa.mecc.platform.RecipePlatform;
import io.github.codaaaaaa.mecc.platform.RecipePlatform.PatternRecipe;
import io.github.codaaaaaa.mecc.platform.RecipePlatform.RecipeBook;
import io.github.codaaaaaa.mecc.platform.thread.ServerThreadGateway;
import io.github.codaaaaaa.mecc.runtime.resources.ResourceCatalog;
import io.github.codaaaaaa.mecc.runtime.resources.TagIndex;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server recipes and registered resources for Pattern Studio. Read once on the server thread (references only),
 * converted on a worker thread, and read again only after a data pack reload; concurrent callers share one
 * refresh. Never fails: while nothing could be read yet, the library is empty.
 */
public final class RecipeLibrary {
    private static final Logger LOGGER = LoggerFactory.getLogger(RecipeLibrary.class);
    private static final Duration CAPTURE_TIMEOUT = Duration.ofSeconds(10);

    /** An indexed recipe book. Immutable apart from its per-locale catalog cache. */
    public static final class Book {
        private final int version;
        private final Map<String, List<PatternRecipe>> byOutput = new HashMap<>();
        private final Map<String, List<PatternRecipe>> byStonecutterInput = new HashMap<>();
        private final Map<String, ResourceDescriptor> registryById = new HashMap<>();
        private final ResourceIndex registry;
        private final Map<String, ResourceCatalog> catalogs = new ConcurrentHashMap<>();

        Book(int version, RecipeBook book) {
            this.version = version;
            this.registry = book.registry();
            for (int i = 0; i < registry.size(); i++) {
                registryById.put(registry.descriptor(i).id().toString(), registry.descriptor(i));
            }
            for (PatternRecipe recipe : book.recipes()) {
                index(byOutput, recipe.output().id(), recipe);
                // Machine recipes are found by any output they guarantee, not only the first.
                recipe.byproducts().forEach(byproduct -> index(byOutput, byproduct.resource().id(), recipe));
                if (recipe.type() == PatternType.STONECUTTING) {
                    for (ResourceDescriptor input : recipe.slots().get(0)) {
                        index(byStonecutterInput, input.id(), recipe);
                    }
                }
            }
        }

        private static void index(Map<String, List<PatternRecipe>> map, ResourceId id, PatternRecipe recipe) {
            add(map, id.toString(), recipe);
            if (id.variant() != null) {
                add(map, id.base().toString(), recipe);
            }
        }

        private static void add(Map<String, List<PatternRecipe>> map, String key, PatternRecipe recipe) {
            List<PatternRecipe> list = map.computeIfAbsent(key, ignored -> new ArrayList<>());
            if (list.isEmpty() || list.get(list.size() - 1) != recipe) {
                list.add(recipe);
            }
        }

        static Book empty() {
            return new Book(Integer.MIN_VALUE, new RecipeBook(List.of(), ResourceIndex.empty(Instant.EPOCH)));
        }

        /**
         * Recipes of a type that make {@code output}. Recipes that list everything they consume come first, then
         * those whose primary output it is.
         */
        public List<PatternRecipe> producing(PatternType type, ResourceId output) {
            String id = output.toString();
            return byOutput.getOrDefault(id, List.of()).stream()
                    .filter(recipe -> recipe.type() == type)
                    .sorted(java.util.Comparator.comparing((PatternRecipe recipe) -> !recipe.complete())
                            .thenComparing(recipe -> !recipe.output().id().toString().equals(id)))
                    .toList();
        }

        /** Stonecutter recipes that accept {@code input}. */
        public List<PatternRecipe> stonecuttingFrom(ResourceId input) {
            return byStonecutterInput.getOrDefault(input.toString(), List.of());
        }

        /** How the game describes a registered resource; variants fall back to their base resource. */
        public Optional<ResourceDescriptor> registered(ResourceId id) {
            ResourceDescriptor exact = registryById.get(id.toString());
            return Optional.ofNullable(exact != null ? exact : registryById.get(id.base().toString()));
        }

        public String snapshotId() {
            return "registry-" + version;
        }

        ResourceCatalog catalog(String locale, TagIndex.Tags tags, Supplier<ResourceNames> names, Map<String, String> modNames) {
            String key = locale + "|" + tags.version();
            ResourceCatalog cached = catalogs.get(key);
            if (cached != null) {
                return cached;
            }
            // Catalogs for older tags are dropped first; a concurrent map must not be changed inside computeIfAbsent.
            catalogs.keySet().removeIf(existing -> existing.startsWith(locale + "|") && !existing.equals(key));
            return catalogs.computeIfAbsent(key, ignored ->
                    new ResourceCatalog(registry, locale, names.get(), modNames, tags.byResource(), tags.version()));
        }
    }

    private final RecipePlatform recipes;
    private final ServerThreadGateway gateway;
    private final Executor workers;
    private volatile Book current = Book.empty();
    private CompletableFuture<Book> refreshing;

    public RecipeLibrary(RecipePlatform recipes, ServerThreadGateway gateway, Executor workers) {
        this.recipes = recipes;
        this.gateway = gateway;
        this.workers = workers;
    }

    /** The book for the current data packs, reading recipes again first if they were reloaded. */
    public synchronized CompletableFuture<Book> book() {
        int version = recipes.recipesVersion();
        Book known = current;
        if (known.version == version) {
            return CompletableFuture.completedFuture(known);
        }
        if (refreshing != null) {
            return refreshing;
        }
        CompletableFuture<Book> refresh = gateway.call("recipes.capture", recipes::captureRecipes, CAPTURE_TIMEOUT)
                .thenApplyAsync(capture -> new Book(version, recipes.describe(capture)), workers)
                .handle((book, error) -> {
                    if (error != null) {
                        LOGGER.warn("ME Control Center could not read server recipes: {}", error.toString());
                        return known;
                    }
                    LOGGER.info("ME Control Center indexed {} registered resources for Pattern Studio", book.registry.size());
                    return book;
                });
        refreshing = refresh;
        refresh.whenComplete((book, error) -> {
            synchronized (this) {
                refreshing = null;
                if (book != null) {
                    current = book;
                }
            }
        });
        return refresh;
    }

    /** The newest book without waiting; may be older than the current data packs, or empty. */
    public Book cached() {
        return current;
    }
}
