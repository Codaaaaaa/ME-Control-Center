package io.github.codaaaaaa.mecc.core.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/** Generation, normalization, and hashing of pairing keys, device tokens, and device IDs. */
public final class SecretCodes {
    /** Unambiguous uppercase alphabet: no I, L, O, 0, 1. */
    static final String PAIRING_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    public static final int PAIRING_KEY_LENGTH = 12;
    private static final String ID_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    public static final int DEVICE_ID_LENGTH = 10;
    private static final int TOKEN_BYTES = 32;

    private SecretCodes() {
    }

    /** A pairing key such as {@code AB7K-3M2Q-9RXF}: about 59 bits of entropy. */
    public static String newPairingKey(SecureRandom random) {
        StringBuilder key = new StringBuilder(PAIRING_KEY_LENGTH + 2);
        for (int i = 0; i < PAIRING_KEY_LENGTH; i++) {
            if (i > 0 && i % 4 == 0) {
                key.append('-');
            }
            key.append(PAIRING_ALPHABET.charAt(random.nextInt(PAIRING_ALPHABET.length())));
        }
        return key.toString();
    }

    /**
     * Canonical form of user-typed pairing key input: separators and whitespace removed, uppercased.
     * Returns {@code null} when it cannot be a valid key.
     */
    public static String normalizePairingKey(String input) {
        if (input == null || input.length() > 64) {
            return null;
        }
        StringBuilder normalized = new StringBuilder(PAIRING_KEY_LENGTH);
        for (char c : input.toUpperCase(Locale.ROOT).toCharArray()) {
            if (c == '-' || c == ' ' || c == '\t') {
                continue;
            }
            if (PAIRING_ALPHABET.indexOf(c) < 0) {
                return null;
            }
            normalized.append(c);
        }
        return normalized.length() == PAIRING_KEY_LENGTH ? normalized.toString() : null;
    }

    /** A 256-bit URL-safe device token. Only its hash is persisted. */
    public static String newDeviceToken(SecureRandom random) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Token verifier stored in the database. Tokens are high-entropy random values, so a fast
     * cryptographic hash is sufficient (no password-style stretching needed).
     */
    public static String hashToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Short, human-typable device identifier used in {@code /mecc revoke <device>}. */
    public static String newDeviceId(SecureRandom random) {
        StringBuilder id = new StringBuilder(DEVICE_ID_LENGTH);
        for (int i = 0; i < DEVICE_ID_LENGTH; i++) {
            id.append(ID_ALPHABET.charAt(random.nextInt(ID_ALPHABET.length())));
        }
        return id.toString();
    }

    public static boolean isPlausibleToken(String token) {
        return token != null && token.length() >= 40 && token.length() <= 64
                && token.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-' || c == '_');
    }
}
