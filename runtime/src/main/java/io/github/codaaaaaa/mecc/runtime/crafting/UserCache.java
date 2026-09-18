package io.github.codaaaaaa.mecc.runtime.crafting;

import io.github.codaaaaaa.mecc.core.auth.AuthViews.UserView;
import io.github.codaaaaaa.mecc.core.persistence.DataStore;
import io.github.codaaaaaa.mecc.core.users.WebUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player names for views that are built often (live CPU and order updates), so each update does not need a
 * database round trip. Names change rarely; entries are refreshed after {@link #TTL}.
 */
public final class UserCache {
    static final Duration TTL = Duration.ofMinutes(5);
    private static final int MAX_ENTRIES = 10_000;

    private final DataStore store;
    private final Clock clock;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    private record Entry(String name, Instant loadedAt) {
    }

    public UserCache(DataStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** Views of the given players; unknown players get a {@code null} name. */
    public CompletableFuture<Map<UUID, UserView>> views(Collection<UUID> players) {
        Instant now = clock.instant();
        Map<UUID, UserView> result = new HashMap<>();
        Set<UUID> missing = new HashSet<>();
        for (UUID player : players) {
            if (player == null) {
                continue;
            }
            Entry entry = entries.get(player);
            if (entry != null && Duration.between(entry.loadedAt(), now).compareTo(TTL) < 0) {
                result.put(player, new UserView(player, entry.name()));
            } else {
                missing.add(player);
            }
        }
        if (missing.isEmpty()) {
            return CompletableFuture.completedFuture(result);
        }
        return store.read(repos -> repos.users().findAll(missing)).thenApply(found -> {
            if (entries.size() > MAX_ENTRIES) {
                entries.clear();
            }
            for (UUID player : missing) {
                WebUser user = found.get(player);
                String name = user == null ? null : user.playerName();
                entries.put(player, new Entry(name, now));
                result.put(player, new UserView(player, name));
            }
            return result;
        });
    }
}
