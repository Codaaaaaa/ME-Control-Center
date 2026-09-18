package io.github.codaaaaaa.mecc.runtime.resources;

import io.github.codaaaaaa.mecc.assets.ResourceNames;
import io.github.codaaaaaa.mecc.core.resources.NameSpan;
import io.github.codaaaaaa.mecc.core.resources.ResourceDescriptor;
import io.github.codaaaaaa.mecc.core.resources.ResourceIndex;
import io.github.codaaaaaa.mecc.core.resources.ResourceQuery.Sort;
import io.github.codaaaaaa.mecc.core.resources.ResourceQuery.TypeFilter;
import io.github.codaaaaaa.mecc.core.resources.ResourceSearch;
import io.github.codaaaaaa.mecc.core.resources.ResourceViews.ResourceView;
import java.text.CollationKey;
import java.text.Collator;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * A snapshot prepared for one UI locale: display names, searchable fields, cached sort orders, and cached
 * query results. Immutable apart from its internal caches; thread-safe. Built off the server thread.
 */
public final class ResourceCatalog {
    private static final int MAX_CACHED_QUERIES = 32;

    private final ResourceIndex index;
    private final int tagsVersion;
    private final Row[] rows;
    private final CollationKey[] nameKeys;
    private final Map<String, Integer> byId;
    private final Map<String, int[]> sortOrders = new ConcurrentHashMap<>();
    private final Map<String, int[]> queryResults = new ConcurrentHashMap<>();

    private record Row(
            String displayName,
            List<NameSpan> displaySpans,
            String name,
            String englishName,
            String id,
            String modId,
            String modName,
            String modDisplayName,
            List<String> tags,
            String type,
            boolean craftable,
            long amount) implements ResourceSearch.Row {
    }

    public ResourceCatalog(ResourceIndex index, String locale, ResourceNames names, Map<String, String> modNames,
                           Map<String, List<String>> tags, int tagsVersion) {
        this.index = index;
        this.tagsVersion = tagsVersion;
        int size = index.size();
        this.rows = new Row[size];
        this.nameKeys = new CollationKey[size];
        this.byId = new HashMap<>(Math.max(16, size * 2));
        Collator collator = Collator.getInstance(locale.startsWith("zh") ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH);
        collator.setStrength(Collator.SECONDARY);

        for (int i = 0; i < size; i++) {
            ResourceDescriptor descriptor = index.descriptor(i);
            List<NameSpan> spans = names.spans(descriptor, locale);
            String display = plain(spans);
            String english = locale.equals(ResourceNames.FALLBACK_LOCALE) ? display : names.displayName(descriptor, ResourceNames.FALLBACK_LOCALE);
            String modDisplay = modNames.getOrDefault(descriptor.modId(), descriptor.modId());
            String id = descriptor.id().toString();
            rows[i] = new Row(
                    display,
                    styled(spans) ? spans : null,
                    display.toLowerCase(Locale.ROOT),
                    english.toLowerCase(Locale.ROOT),
                    id,
                    descriptor.modId().toLowerCase(Locale.ROOT),
                    modDisplay.toLowerCase(Locale.ROOT),
                    modDisplay,
                    tags.getOrDefault(descriptor.id().base().toString(), List.of()),
                    descriptor.id().type(),
                    index.craftable(i),
                    index.amount(i));
            nameKeys[i] = collator.getCollationKey(display);
            byId.put(id, i);
        }
    }

    private static String plain(List<NameSpan> spans) {
        if (spans.size() == 1) {
            return spans.get(0).text();
        }
        StringBuilder text = new StringBuilder();
        spans.forEach(span -> text.append(span.text()));
        return text.toString();
    }

    /** Whether the styling is worth sending: an unstyled name is fully described by its plain form. */
    private static boolean styled(List<NameSpan> spans) {
        return spans.stream().anyMatch(NameSpan::styled);
    }

    public int tagsVersion() {
        return tagsVersion;
    }

    public ResourceIndex index() {
        return index;
    }

    /** Matching entry positions in the requested order. Cached per query. */
    public int[] query(String search, TypeFilter type, Sort sort, boolean descending) {
        String key = type + "|" + sort + "|" + descending + "|" + search.toLowerCase(Locale.ROOT);
        int[] cached = queryResults.get(key);
        if (cached != null) {
            return cached;
        }
        Predicate<ResourceSearch.Row> matcher = ResourceSearch.parse(search);
        int[] order = sortOrder(sort, descending);
        int[] matches = new int[order.length];
        int count = 0;
        for (int position : order) {
            Row row = rows[position];
            if (matchesType(row.type(), type) && matcher.test(row)) {
                matches[count++] = position;
            }
        }
        int[] result = Arrays.copyOf(matches, count);
        if (queryResults.size() >= MAX_CACHED_QUERIES) {
            queryResults.clear();
        }
        queryResults.put(key, result);
        return result;
    }

    public ResourceView view(int position) {
        Row row = rows[position];
        ResourceDescriptor descriptor = index.descriptor(position);
        long crafting = index.craftingAmount(position);
        return new ResourceView(row.id(), row.type(), row.displayName(), row.displaySpans(), descriptor.modId(), row.modDisplayName(),
                row.amount(), row.craftable(), crafting == ResourceIndex.NOT_CRAFTING ? null : crafting,
                descriptor.unit(), descriptor.iconKey());
    }

    public Optional<Integer> find(String resourceId) {
        return Optional.ofNullable(byId.get(resourceId));
    }

    public List<String> tags(int position) {
        return rows[position].tags();
    }

    private int[] sortOrder(Sort sort, boolean descending) {
        return sortOrders.computeIfAbsent(sort + "|" + descending, key -> {
            Integer[] positions = new Integer[rows.length];
            for (int i = 0; i < positions.length; i++) {
                positions[i] = i;
            }
            Comparator<Integer> byName = (a, b) -> nameKeys[a].compareTo(nameKeys[b]);
            Comparator<Integer> tieBreak = byName.thenComparing(i -> rows[i].id());
            Comparator<Integer> comparator = switch (sort) {
                case NAME -> tieBreak;
                case AMOUNT -> Comparator.<Integer>comparingLong(i -> rows[i].amount()).thenComparing(tieBreak);
                case MOD -> Comparator.<Integer, String>comparing(i -> rows[i].modName()).thenComparing(tieBreak);
                case CRAFTABLE -> Comparator.<Integer, Boolean>comparing(i -> !rows[i].craftable()).thenComparing(tieBreak);
            };
            Arrays.sort(positions, descending ? comparator.reversed() : comparator);
            return Arrays.stream(positions).mapToInt(Integer::intValue).toArray();
        });
    }

    private static boolean matchesType(String type, TypeFilter filter) {
        return switch (filter) {
            case ALL -> true;
            case ITEM -> type.equals("item");
            case FLUID -> type.equals("fluid");
            case OTHER -> !type.equals("item") && !type.equals("fluid");
        };
    }
}
