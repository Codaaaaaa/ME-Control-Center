package io.github.codaaaaaa.mecc.core.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * An IP address or CIDR block such as {@code 10.0.0.0/8}, {@code 127.0.0.1}, or {@code ::1}.
 * Parsing never performs DNS lookups.
 */
public final class IpRange {
    // Only literals: a dotted IPv4 address, or anything with a colon (IPv6). Hex-only words such as "cafe"
    // or "deadbeef" would otherwise pass and make InetAddress.getByName perform a DNS lookup.
    private static final Pattern LITERAL = Pattern.compile("^(\\d{1,3}(\\.\\d{1,3}){3}|[0-9A-Fa-f.]*:[0-9A-Fa-f:.]*)$");

    private final byte[] network;
    private final int prefixLength;
    private final String text;

    private IpRange(byte[] network, int prefixLength, String text) {
        this.network = network;
        this.prefixLength = prefixLength;
        this.text = text;
    }

    public static Optional<IpRange> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String trimmed = value.strip();
        int slash = trimmed.indexOf('/');
        String addressPart = slash < 0 ? trimmed : trimmed.substring(0, slash);
        Optional<byte[]> address = parseLiteral(addressPart);
        if (address.isEmpty()) {
            return Optional.empty();
        }
        int maxBits = address.get().length * 8;
        int prefix = maxBits;
        if (slash >= 0) {
            try {
                prefix = Integer.parseInt(trimmed.substring(slash + 1));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
            if (prefix < 0 || prefix > maxBits) {
                return Optional.empty();
            }
        }
        return Optional.of(new IpRange(mask(address.get(), prefix), prefix, trimmed));
    }

    /** Parses a literal IPv4/IPv6 address without DNS. IPv4-mapped IPv6 addresses become IPv4. */
    public static Optional<byte[]> parseLiteral(String value) {
        if (value == null || value.isEmpty() || !LITERAL.matcher(value).matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.getByName(value).getAddress());
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
    }

    public boolean contains(String address) {
        String candidate = address;
        // Jetty reports IPv6 remote addresses in brackets.
        if (candidate != null && candidate.startsWith("[") && candidate.endsWith("]")) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        return parseLiteral(candidate)
                .filter(bytes -> bytes.length == network.length)
                .map(bytes -> Arrays.equals(mask(bytes, prefixLength), network))
                .orElse(false);
    }

    @Override
    public String toString() {
        return text;
    }

    private static byte[] mask(byte[] address, int prefix) {
        byte[] result = address.clone();
        for (int i = 0; i < result.length; i++) {
            int bitsInByte = Math.max(0, Math.min(8, prefix - i * 8));
            int byteMask = bitsInByte == 0 ? 0 : (0xFF << (8 - bitsInByte)) & 0xFF;
            result[i] = (byte) (result[i] & byteMask);
        }
        return result;
    }
}
