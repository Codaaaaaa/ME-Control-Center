package io.github.codaaaaaa.mecc.core.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Embedded web server settings ({@code [web]} section of {@code mecc.toml}).
 *
 * @param enabled       whether the embedded web server starts at all
 * @param host          interface to bind, e.g. {@code 127.0.0.1} or {@code 0.0.0.0}
 * @param port          TCP port
 * @param maxThreads    upper bound of the HTTP worker pool
 * @param publicBaseUrl URL players use to reach ME Control Center (e.g. behind a reverse proxy), or empty when unknown
 */
public record WebConfig(boolean enabled, String host, int port, int maxThreads, String publicBaseUrl) {
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 18181;
    public static final int DEFAULT_MAX_THREADS = 32;
    public static final int MIN_THREADS = 8;
    public static final int MAX_THREADS = 256;

    // IPv4/hostname characters, or a bracket-less IPv6 literal. Resolution happens at bind time.
    private static final Pattern HOST_PATTERN = Pattern.compile("^[A-Za-z0-9.\\-:%]+$");

    public WebConfig {
        publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.strip();
    }

    public static WebConfig defaults() {
        return new WebConfig(true, DEFAULT_HOST, DEFAULT_PORT, DEFAULT_MAX_THREADS, "");
    }

    /** Returns human-readable problems; empty when valid. */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (host == null || host.isBlank()) {
            problems.add("web.host must not be empty");
        } else if (!HOST_PATTERN.matcher(host).matches()) {
            problems.add("web.host is not a valid host name or IP address: " + host);
        }
        if (port < 1 || port > 65535) {
            problems.add("web.port must be between 1 and 65535, got " + port);
        }
        if (maxThreads < MIN_THREADS || maxThreads > MAX_THREADS) {
            problems.add("web.max_threads must be between " + MIN_THREADS + " and " + MAX_THREADS + ", got " + maxThreads);
        }
        if (!publicBaseUrl.isEmpty() && publicOrigin() == null) {
            problems.add("web.public_base_url must be empty or an absolute http(s) URL, got " + publicBaseUrl);
        }
        return problems;
    }

    /** Origin ({@code scheme://host[:port]}) of {@link #publicBaseUrl}, or {@code null} when not configured/invalid. */
    public String publicOrigin() {
        return originOf(publicBaseUrl);
    }

    /** Normalizes an absolute http(s) URL to its origin, or returns {@code null}. */
    public static String originOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(url.strip());
            String scheme = uri.getScheme();
            if (uri.getHost() == null || scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return null;
            }
            String origin = scheme.toLowerCase() + "://" + uri.getHost().toLowerCase();
            return uri.getPort() >= 0 ? origin + ":" + uri.getPort() : origin;
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
