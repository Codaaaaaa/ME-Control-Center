package io.github.codaaaaaa.mecc.core.crafting;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface CraftingOrderRepository {

    void insert(CraftingOrder order);

    Optional<CraftingOrder> find(UUID id);

    /** Stores every mutable field of {@code order}. Returns {@code false} if the order no longer exists. */
    boolean update(CraftingOrder order);

    /**
     * Orders of a network in the given states, newest first.
     *
     * @param before only orders after this position, or {@code null} to start with the newest
     */
    List<CraftingOrder> list(UUID networkId, Set<OrderState> states, int limit, Cursor before);

    /** Orders in the given states across all networks, e.g. every active order at startup. */
    List<CraftingOrder> listByStates(Set<OrderState> states);

    void appendEvent(OrderEvent event);

    /** History of one order, oldest first. */
    List<OrderEvent> events(UUID orderId);

    /** Keyset pagination position: orders are listed by creation time, then id, both descending. */
    record Cursor(Instant createdAt, UUID id) {
        public String encode() {
            return createdAt.toEpochMilli() + "_" + id;
        }

        public static Optional<Cursor> decode(String text) {
            if (text == null || text.isBlank() || text.length() > 80) {
                return Optional.empty();
            }
            int separator = text.indexOf('_');
            if (separator < 1) {
                return Optional.empty();
            }
            try {
                return Optional.of(new Cursor(Instant.ofEpochMilli(Long.parseLong(text.substring(0, separator))),
                        UUID.fromString(text.substring(separator + 1))));
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }
    }
}
