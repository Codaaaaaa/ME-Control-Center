package io.github.codaaaaaa.mecc.core.alerts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AlertRuleTest {
    private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");

    private static AlertRule rule(AlertState state, Instant notifiedAt) {
        return new AlertRule(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), AlertType.NETWORK_OFFLINE, null, null, null,
                30, true, state, notifiedAt, NOW.minusSeconds(86_400));
    }

    @Test
    void firesWhenTheConditionStartsAndResolvesWhenItStops() {
        assertEquals(AlertState.FIRING, rule(AlertState.OK, null).next(true, NOW));
        assertEquals(AlertState.FIRING, rule(AlertState.FIRING, NOW.minusSeconds(60)).next(true, NOW), "stays firing");
        assertEquals(AlertState.OK, rule(AlertState.FIRING, NOW.minusSeconds(60)).next(false, NOW));
        assertEquals(AlertState.OK, rule(AlertState.OK, null).next(false, NOW));
    }

    @Test
    void aConditionWithinTheCooldownWaitsAndFiresOnceItPassed() {
        AlertRule recent = rule(AlertState.OK, NOW.minusSeconds(10 * 60));
        assertEquals(AlertState.SUPPRESSED, recent.next(true, NOW));
        AlertRule suppressed = rule(AlertState.SUPPRESSED, NOW.minusSeconds(10 * 60));
        assertEquals(AlertState.SUPPRESSED, suppressed.next(true, NOW.plusSeconds(19 * 60)));
        assertEquals(AlertState.FIRING, suppressed.next(true, NOW.plusSeconds(20 * 60)), "exactly at the cooldown");
        assertEquals(AlertState.OK, suppressed.next(false, NOW), "recovers silently");
    }
}
