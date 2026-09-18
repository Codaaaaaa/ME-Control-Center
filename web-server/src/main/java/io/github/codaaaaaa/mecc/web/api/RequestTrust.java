package io.github.codaaaaaa.mecc.web.api;

import io.github.codaaaaaa.mecc.core.auth.SecretCodes;
import io.github.codaaaaaa.mecc.core.config.WebConfig;
import java.util.Locale;
import java.util.Set;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;

/** Device-token extraction and browser-origin checks shared by the REST API and the WebSocket endpoint. */
public final class RequestTrust {
    public static final String DEVICE_COOKIE = "mecc_device";

    private RequestTrust() {
    }

    /**
     * A presented device token.
     *
     * @param token      the token, or {@code null} when none (or an implausible one) was presented
     * @param fromCookie whether it came from the device cookie rather than an {@code Authorization} header
     */
    public record PresentedToken(String token, boolean fromCookie) {
    }

    /** The {@code Authorization: Bearer} token if present, otherwise the device cookie. */
    public static PresentedToken presentedToken(Request request) {
        String authorization = request.getHeaders().get(HttpHeader.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = authorization.substring(7).strip();
            return new PresentedToken(SecretCodes.isPlausibleToken(token) ? token : null, false);
        }
        for (HttpCookie cookie : Request.getCookies(request)) {
            if (DEVICE_COOKIE.equals(cookie.getName())) {
                String value = cookie.getValue();
                return new PresentedToken(SecretCodes.isPlausibleToken(value) ? value : null, true);
            }
        }
        return new PresentedToken(null, false);
    }

    /**
     * Whether a request may act on behalf of the browser's cookie: no {@code Origin} (non-browser client or a
     * browser that omits it for same-origin requests), an explicitly allowed origin, or the origin the
     * browser used to reach this server.
     */
    public static boolean originAllowed(Request request, Set<String> allowedOrigins) {
        String fetchSite = request.getHeaders().get("Sec-Fetch-Site");
        if (fetchSite != null && fetchSite.equalsIgnoreCase("cross-site")) {
            return false;
        }
        String origin = request.getHeaders().get(HttpHeader.ORIGIN);
        if (origin == null) {
            return true;
        }
        String normalized = WebConfig.originOf(origin);
        if (normalized == null) {
            return false;
        }
        if (allowedOrigins.contains(normalized)) {
            return true;
        }
        String host = request.getHeaders().get(HttpHeader.HOST);
        String originAuthority = normalized.substring(normalized.indexOf("://") + 3);
        return host != null && authorityMatches(originAuthority, host.toLowerCase(Locale.ROOT), normalized.startsWith("https"));
    }

    private static boolean authorityMatches(String originAuthority, String hostHeader, boolean https) {
        if (originAuthority.equals(hostHeader)) {
            return true;
        }
        String defaultPort = https ? ":443" : ":80";
        return (originAuthority + defaultPort).equals(hostHeader) || originAuthority.equals(hostHeader + defaultPort);
    }
}
