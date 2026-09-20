package io.github.codaaaaaa.mecc.core.automation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RestockRepository {

    void insert(RestockRule rule);

    /** Replaces the editable fields and the automation state. */
    boolean update(RestockRule rule);

    Optional<RestockRule> find(UUID id);

    /** Every rule of one network, oldest first. */
    List<RestockRule> list(UUID networkId);

    /** Every enabled rule of every network, for the restock engine. */
    List<RestockRule> enabledRules();

    int countByNetwork(UUID networkId);

    boolean delete(UUID id);

    /** Kill switch: disables every enabled rule, of one network or (with {@code null}) of all. Returns how many. */
    int disableAll(UUID networkId);
}
