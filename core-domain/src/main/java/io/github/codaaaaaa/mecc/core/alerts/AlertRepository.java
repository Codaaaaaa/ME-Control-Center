package io.github.codaaaaaa.mecc.core.alerts;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AlertRepository {

    void insertRule(AlertRule rule);

    /** Replaces threshold, cooldown, enabled, state, and notification time. */
    boolean updateRule(AlertRule rule);

    /** Records an evaluation result without touching what the player edits. */
    boolean setState(UUID id, AlertState state, Instant notifiedAt);

    Optional<AlertRule> findRule(UUID id);

    /** A player's rules on one network, oldest first. */
    List<AlertRule> rules(UUID playerUuid, UUID networkId);

    /** Every enabled rule, for evaluation. */
    List<AlertRule> enabledRules();

    int countRules(UUID playerUuid);

    /** Deletes a rule and its events. */
    boolean deleteRule(UUID id);

    /** Deletes a player's rules (and their events) on one network. Returns the number of rules deleted. */
    int deleteRules(UUID playerUuid, UUID networkId);

    /** Stores an event and returns its ID. */
    long appendEvent(AlertEvent event);

    /**
     * A player's events, newest first.
     *
     * @param networkId only this network, or {@code null} for all
     * @param beforeId  only events older than this ID, or {@code null}
     */
    List<AlertEvent> events(UUID playerUuid, UUID networkId, Long beforeId, int limit);

    int purgeEvents(Instant before);

    Optional<AlertSettings> settings(UUID playerUuid);

    void putSettings(AlertSettings settings);
}
