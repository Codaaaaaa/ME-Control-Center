package io.github.codaaaaaa.mecc.runtime.alerts;

import io.github.codaaaaaa.mecc.core.error.MeccException;
import io.github.codaaaaaa.mecc.core.net.IpRange;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which webhook addresses players may use (spec section 36). Any player can enter one, so the server would make
 * requests on their behalf: without these checks that reaches the server's own network (SSRF). Discord webhooks
 * must point at Discord; generic ones may not resolve to loopback, private, or otherwise internal addresses unless
 * the admin allows it.
 */
final class WebhookTargets {
    static final int MAX_URL_LENGTH = 512;
    private static final Set<String> DISCORD_HOSTS =
            Set.of("discord.com", "discordapp.com", "ptb.discord.com", "canary.discord.com");
    /** Internal ranges {@link InetAddress} has no predicate for: "this network", CGNAT, IETF, benchmark, reserved, ULA. */
    private static final List<IpRange> INTERNAL = List.of("0.0.0.0/8", "100.64.0.0/10", "192.0.0.0/24",
            "198.18.0.0/15", "240.0.0.0/4", "fc00::/7").stream().map(range -> IpRange.parse(range).orElseThrow()).toList();

    private WebhookTargets() {
    }

    /** A Discord webhook URL, or {@code null} for blank. Fails with {@code VALIDATION_FAILED}. */
    static String discord(String url) {
        URI uri = parse(url, "discordWebhookUrl");
        if (uri == null) {
            return null;
        }
        if (!"https".equals(uri.getScheme()) || !DISCORD_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))
                || uri.getPort() != -1 || uri.getRawPath() == null || !uri.getRawPath().startsWith("/api/webhooks/")) {
            throw MeccException.validation("discordWebhookUrl",
                    "Use the webhook URL Discord shows: https://discord.com/api/webhooks/...");
        }
        return uri.toString();
    }

    /** A generic webhook URL, or {@code null} for blank. Fails with {@code VALIDATION_FAILED}. */
    static String generic(String url) {
        URI uri = parse(url, "webhookUrl");
        if (uri == null) {
            return null;
        }
        if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) {
            throw MeccException.validation("webhookUrl", "The webhook URL must start with https:// or http://");
        }
        return uri.toString();
    }

    private static URI parse(String url, String field) {
        String text = url == null ? "" : url.strip();
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() > MAX_URL_LENGTH) {
            throw MeccException.validation(field, "The URL is too long");
        }
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException e) {
            throw MeccException.validation(field, "Not a valid URL");
        }
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw MeccException.validation(field, "Not a valid URL");
        }
        uri = uri.normalize();
        return uri;
    }

    /**
     * Resolves the host and refuses internal addresses. Blocking (DNS); run off the server thread.
     *
     * <p>ponytail: the HTTP client resolves the host again when it connects; the JVM's 30 s DNS cache makes that the
     * same answer in practice. Pin the checked address in a custom socket factory if DNS rebinding becomes a concern.
     */
    static void checkAddress(String host, boolean allowPrivate) throws UnknownHostException {
        if (allowPrivate) {
            return;
        }
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (internal(address)) {
                throw new UnknownHostException(host + " resolves to an internal address, which webhooks may not use");
            }
        }
    }

    static boolean internal(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        String literal = address.getHostAddress();
        if (address instanceof Inet6Address && literal.contains("%")) {
            literal = literal.substring(0, literal.indexOf('%'));
        }
        String text = literal;
        return INTERNAL.stream().anyMatch(range -> range.contains(text));
    }
}
