package io.github.codaaaaaa.mecc.core.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.codaaaaaa.mecc.core.error.ErrorCode;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import io.github.codaaaaaa.mecc.core.users.PlayerProfile;
import io.github.codaaaaaa.mecc.test.MutableClock;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PairingServiceTest {
    private final MutableClock clock = new MutableClock();
    private final PairingService pairing = new PairingService(Duration.ofMinutes(5), clock, new SecureRandom());
    private final PlayerProfile steve = new PlayerProfile(UUID.randomUUID(), "Steve");
    private final PlayerProfile alex = new PlayerProfile(UUID.randomUUID(), "Alex");

    @Test
    void keyIsRedeemableOnceAndTiedToThePlayer() {
        PairingService.PairingKey key = pairing.issue(steve);

        assertTrue(key.key().matches("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}"), key.key());
        assertEquals(steve, pairing.redeem(key.key()).orElseThrow().player());
        assertTrue(pairing.redeem(key.key()).isEmpty(), "replayed key must be rejected");
    }

    @Test
    void acceptsLenientInput() {
        String key = pairing.issue(steve).key();
        assertTrue(pairing.redeem("  " + key.toLowerCase().replace("-", " ") + " ").isPresent());
    }

    @Test
    void expiredKeyIsRejected() {
        String key = pairing.issue(steve).key();
        clock.advance(Duration.ofMinutes(5));
        assertTrue(pairing.redeem(key).isEmpty());
        assertEquals(0, pairing.outstandingKeys());
    }

    @Test
    void newKeyInvalidatesThePlayersPreviousKey() {
        String first = pairing.issue(steve).key();
        String second = pairing.issue(steve).key();
        String other = pairing.issue(alex).key();

        assertNotEquals(first, second);
        assertTrue(pairing.redeem(first).isEmpty());
        assertTrue(pairing.redeem(second).isPresent());
        assertTrue(pairing.redeem(other).isPresent(), "other players' keys are unaffected");
    }

    @Test
    void malformedAndUnknownKeysAreRejected() {
        assertTrue(pairing.redeem(null).isEmpty());
        assertTrue(pairing.redeem("").isEmpty());
        assertTrue(pairing.redeem("AAAA-AAAA-AAA0").isEmpty(), "0 is not in the alphabet");
        assertTrue(pairing.redeem("AAAA-AAAA-AAAA").isEmpty());
    }

    @Test
    void issuingIsRateLimitedPerPlayer() {
        for (int i = 0; i < PairingService.MAX_KEYS_PER_WINDOW; i++) {
            pairing.issue(steve);
        }
        MeccException e = assertThrows(MeccException.class, () -> pairing.issue(steve));
        assertEquals(ErrorCode.RATE_LIMITED, e.code());
        pairing.issue(alex);

        clock.advance(PairingService.ISSUE_WINDOW);
        pairing.issue(steve);
    }
}
