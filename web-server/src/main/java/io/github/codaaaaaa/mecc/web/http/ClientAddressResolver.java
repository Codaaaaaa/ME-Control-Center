package io.github.codaaaaaa.mecc.web.http;

import io.github.codaaaaaa.mecc.core.net.IpRange;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Determines the real client address and scheme (spec section 36: no trust of arbitrary proxy headers).
 *
 * <p>{@code X-Forwarded-For} and {@code X-Forwarded-Proto} are only honored when the TCP peer is a
 * configured trusted proxy. The chain is walked from the right, skipping trusted hops, so a client cannot
 * spoof its address by prepending entries.
 */
public final class ClientAddressResolver {
    private final List<IpRange> trustedProxies;

    public ClientAddressResolver(List<IpRange> trustedProxies) {
        this.trustedProxies = List.copyOf(trustedProxies);
    }

    public String clientAddress(String remoteAddress, List<String> forwardedForHeaders) {
        String address = stripBrackets(remoteAddress);
        if (!isTrusted(address)) {
            return address;
        }
        List<String> hops = new ArrayList<>();
        for (String header : forwardedForHeaders) {
            for (String hop : header.split(",")) {
                if (!hop.isBlank()) {
                    hops.add(stripBrackets(hop.strip()));
                }
            }
        }
        for (int i = hops.size() - 1; i >= 0; i--) {
            String hop = hops.get(i);
            if (IpRange.parseLiteral(hop).isEmpty()) {
                return address; // Malformed entry: stop at the last address we can vouch for.
            }
            address = hop;
            if (!isTrusted(hop)) {
                return hop;
            }
        }
        return address;
    }

    /** Whether the original request used HTTPS, either directly or according to a trusted proxy. */
    public boolean isSecure(boolean connectionSecure, String remoteAddress, String forwardedProto) {
        if (connectionSecure) {
            return true;
        }
        if (forwardedProto == null || !isTrusted(stripBrackets(remoteAddress))) {
            return false;
        }
        String first = forwardedProto.split(",")[0].strip().toLowerCase(Locale.ROOT);
        return first.equals("https");
    }

    private boolean isTrusted(String address) {
        return trustedProxies.stream().anyMatch(range -> range.contains(address));
    }

    private static String stripBrackets(String address) {
        if (address != null && address.startsWith("[") && address.endsWith("]")) {
            return address.substring(1, address.length() - 1);
        }
        return address;
    }
}
