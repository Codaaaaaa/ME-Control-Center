package io.github.codaaaaaa.mecc.web.api;

import io.github.codaaaaaa.mecc.core.auth.ClientInfo;
import io.github.codaaaaaa.mecc.core.auth.Session;
import io.github.codaaaaaa.mecc.core.error.ErrorCode;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import io.github.codaaaaaa.mecc.web.json.JsonCodec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** Transport-neutral view of an API request handed to endpoints. */
public final class ApiRequest {
    private static final int MAX_QUERY_LENGTH = 4096;

    private final String method;
    private final String path;
    private final String query;
    private final Map<String, String> pathParameters;
    private final byte[] body;
    private final ClientInfo client;
    private final Session session;
    private final JsonCodec json;
    private final UnaryOperator<String> headers;
    private Map<String, String> queryParameters;

    public ApiRequest(String method, String path, String query, Map<String, String> pathParameters, byte[] body,
                      ClientInfo client, Session session, JsonCodec json, UnaryOperator<String> headers) {
        this.method = method;
        this.path = path;
        this.query = query;
        this.pathParameters = Map.copyOf(pathParameters);
        this.body = body == null ? new byte[0] : body;
        this.client = client;
        this.session = session;
        this.json = json;
        this.headers = headers;
    }

    public String method() {
        return method;
    }

    /** Decoded path, e.g. {@code /api/v1/status}. */
    public String path() {
        return path;
    }

    /** Raw query string, or {@code null}. */
    public String query() {
        return query;
    }

    public String pathParameter(String name) {
        String value = pathParameters.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Route has no path parameter " + name);
        }
        return value;
    }

    /** Parses a UUID path parameter; a malformed value fails with {@code notFound} since no such resource can exist. */
    public UUID uuidParameter(String name, ErrorCode notFound) {
        try {
            return UUID.fromString(pathParameter(name));
        } catch (IllegalArgumentException e) {
            throw new MeccException(notFound, "Not found");
        }
    }

    /** Decoded query parameter; the first occurrence wins. */
    public Optional<String> queryParameter(String name) {
        if (queryParameters == null) {
            queryParameters = parseQuery(query);
        }
        return Optional.ofNullable(queryParameters.get(name));
    }

    public String queryParameter(String name, String fallback) {
        return queryParameter(name).filter(value -> !value.isEmpty()).orElse(fallback);
    }

    /** Numeric query parameter clamped into range; a non-numeric value fails validation. */
    public int intParameter(String name, int fallback, int min, int max) {
        Optional<String> value = queryParameter(name).filter(text -> !text.isEmpty());
        if (value.isEmpty()) {
            return fallback;
        }
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(value.get())));
        } catch (NumberFormatException e) {
            throw MeccException.validation(name, name + " must be a number");
        }
    }

    public boolean boolParameter(String name, boolean fallback) {
        return queryParameter(name).map(value -> value.equals("true") || value.equals("1")).orElse(fallback);
    }

    /** Enum query parameter, case-insensitive; an unknown value fails validation. */
    public <E extends Enum<E>> E enumParameter(String name, Class<E> type, E fallback) {
        Optional<String> value = queryParameter(name).filter(text -> !text.isEmpty());
        if (value.isEmpty()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.get().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw MeccException.validation(name, name + " is not one of the supported values");
        }
    }

    /** Request header value, or {@code null}. */
    public String header(String name) {
        return headers == null ? null : headers.apply(name);
    }

    public ClientInfo client() {
        return client;
    }

    /** The authenticated session. Always present on authenticated routes. */
    public Session session() {
        if (session == null) {
            throw new MeccException(ErrorCode.UNAUTHENTICATED, "Sign in by pairing this browser first");
        }
        return session;
    }

    public Optional<Session> optionalSession() {
        return Optional.ofNullable(session);
    }

    /** Parses the JSON body. A missing or malformed body fails with {@code BAD_REQUEST}. */
    public <T> T body(Class<T> type) {
        if (body.length == 0) {
            throw new MeccException(ErrorCode.BAD_REQUEST, "A JSON request body is required");
        }
        try {
            T value = json.fromBytes(body, type);
            if (value == null) {
                throw new MeccException(ErrorCode.BAD_REQUEST, "A JSON request body is required");
            }
            return value;
        } catch (MeccException e) {
            throw e;
        } catch (Exception e) {
            throw new MeccException(ErrorCode.BAD_REQUEST, "The request body is not valid JSON for this endpoint");
        }
    }

    static Map<String, String> parseQuery(String query) {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (query == null || query.isEmpty() || query.length() > MAX_QUERY_LENGTH) {
            return parameters;
        }
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            try {
                parameters.putIfAbsent(URLDecoder.decode(name, StandardCharsets.UTF_8),
                        URLDecoder.decode(value, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                // Malformed percent-encoding: ignore this parameter rather than failing the request.
            }
        }
        return parameters;
    }
}
