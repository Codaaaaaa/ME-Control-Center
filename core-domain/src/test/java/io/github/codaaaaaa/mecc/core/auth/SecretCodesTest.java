package io.github.codaaaaaa.mecc.core.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SecretCodesTest {
    private final SecureRandom random = new SecureRandom();

    @Test
    void deviceTokensAreHighEntropyAndHashedDeterministically() {
        String token = SecretCodes.newDeviceToken(random);
        assertEquals(43, token.length());
        assertTrue(SecretCodes.isPlausibleToken(token));
        assertEquals(SecretCodes.hashToken(token), SecretCodes.hashToken(token));
        assertNotEquals(token, SecretCodes.hashToken(token));
        assertEquals(64, SecretCodes.hashToken(token).length());

        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertTrue(tokens.add(SecretCodes.newDeviceToken(random)));
        }
    }

    @Test
    void rejectsImplausibleTokens() {
        assertFalse(SecretCodes.isPlausibleToken(null));
        assertFalse(SecretCodes.isPlausibleToken("short"));
        assertFalse(SecretCodes.isPlausibleToken("x".repeat(43) + ";"));
    }

    @Test
    void normalizesPairingKeys() {
        assertEquals("AB7K3M2Q9RXF", SecretCodes.normalizePairingKey("ab7k-3m2q 9rxf"));
        assertNull(SecretCodes.normalizePairingKey("AB7K-3M2Q-9RX"));
        assertNull(SecretCodes.normalizePairingKey("AB7K-3M2Q-9RXO"));
    }

    @Test
    void deviceIdsUseTheShortAlphabet() {
        String id = SecretCodes.newDeviceId(random);
        assertTrue(id.matches("[a-z2-9]{10}"), id);
    }
}
