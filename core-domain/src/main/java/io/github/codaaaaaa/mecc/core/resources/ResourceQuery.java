package io.github.codaaaaaa.mecc.core.resources;

import java.util.Objects;

/**
 * A terminal page request.
 *
 * @param search     search text (see {@link ResourceSearch})
 * @param sort       sort key
 * @param descending sort direction
 * @param type       resource type filter
 * @param offset     first entry
 * @param limit      maximum entries
 * @param snapshotId snapshot to read from for consistent paging, or {@code null} for the latest
 * @param locale     UI locale for names and name sorting, e.g. {@code zh_cn}
 */
public record ResourceQuery(
        String search,
        Sort sort,
        boolean descending,
        TypeFilter type,
        int offset,
        int limit,
        String snapshotId,
        String locale) {

    public static final int MAX_LIMIT = 500;

    public ResourceQuery {
        search = search == null ? "" : search.strip();
        Objects.requireNonNull(sort, "sort");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(locale, "locale");
        if (offset < 0 || limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("offset must be >= 0 and limit between 1 and " + MAX_LIMIT);
        }
    }

    /** Sort orders (spec section 7.4). */
    public enum Sort {
        NAME,
        AMOUNT,
        MOD,
        /** Craftable resources first, then by name. */
        CRAFTABLE
    }

    public enum TypeFilter {
        ALL,
        ITEM,
        FLUID,
        OTHER
    }
}
