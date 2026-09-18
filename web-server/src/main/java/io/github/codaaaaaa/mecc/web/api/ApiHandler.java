package io.github.codaaaaaa.mecc.web.api;

import io.github.codaaaaaa.mecc.core.auth.ClientInfo;
import io.github.codaaaaaa.mecc.core.auth.Session;
import io.github.codaaaaaa.mecc.core.error.ErrorCode;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import io.github.codaaaaaa.mecc.web.api.ApiRoutes.Access;
import io.github.codaaaaaa.mecc.web.api.ApiRoutes.Match;
import io.github.codaaaaaa.mecc.web.api.ApiRoutes.Route;
import io.github.codaaaaaa.mecc.web.http.BoundedBodyReader;
import io.github.codaaaaaa.mecc.web.http.ClientAddressResolver;
import io.github.codaaaaaa.mecc.web.json.JsonCodec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dispatches {@code /api/**} requests to {@link ApiEndpoint}s and writes JSON responses asynchronously.
 *
 * <p>Pipeline: route match → client/scheme resolution (trusted proxies only) → per-address rate limit → CSRF
 * defenses for state-changing methods (origin check, JSON content type) → bounded body read → device-token
 * authentication → per-player write limit → endpoint → JSON response.
 */
public final class ApiHandler extends Handler.Abstract.NonBlocking {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiHandler.class);
    private static final String JSON = "application/json; charset=utf-8";
    public static final String DEVICE_COOKIE = RequestTrust.DEVICE_COOKIE;
    /** Browsers cap cookie lifetime at 400 days; the cookie is re-sent periodically while in use. */
    static final long DEVICE_COOKIE_MAX_AGE_SECONDS = 400L * 24 * 60 * 60;
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    /** Immutable, browser-cached game assets: a freshly opened terminal loads hundreds at once. */
    private static final Set<String> UNLIMITED_PATHS = Set.of("/api/v1/icons");

    private final ApiRoutes routes;
    private final JsonCodec json;
    private final ApiSecurity security;
    private final SessionResolver sessions;
    private final ClientAddressResolver addresses;
    private final RequestLimits limits;

    public ApiHandler(ApiRoutes routes, JsonCodec json, ApiSecurity security, SessionResolver sessions,
                      RequestLimits limits) {
        this.routes = routes;
        this.json = json;
        this.security = security;
        this.sessions = sessions;
        this.limits = limits;
        this.addresses = new ClientAddressResolver(security.trustedProxies());
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) {
        String path = Request.getPathInContext(request);
        if (!path.equals("/api") && !path.startsWith(ApiRoutes.API_PREFIX)) {
            return false;
        }

        Match match = routes.match(path).orElse(null);
        if (match == null) {
            writeError(response, callback, new MeccException(ErrorCode.NOT_FOUND, "Unknown API endpoint: " + path));
            return true;
        }
        String method = request.getMethod();
        Route route = match.routes().get(method);
        if (route == null) {
            response.getHeaders().put(HttpHeader.ALLOW, String.join(", ", match.routes().keySet()));
            writeError(response, callback, new MeccException(ErrorCode.METHOD_NOT_ALLOWED,
                    "Method " + method + " is not allowed for " + path));
            return true;
        }

        String remote = Request.getRemoteAddr(request);
        ClientInfo client = new ClientInfo(
                addresses.clientAddress(remote, request.getHeaders().getValuesList(HttpHeader.X_FORWARDED_FOR)),
                request.getHeaders().get(HttpHeader.USER_AGENT));
        boolean secure = security.alwaysSecureCookie()
                || addresses.isSecure(request.isSecure(), remote, request.getHeaders().get(HttpHeader.X_FORWARDED_PROTO));

        try {
            if (!UNLIMITED_PATHS.contains(path) && !limits.allowRequest(client.address())) {
                throw new MeccException(ErrorCode.RATE_LIMITED, "Too many requests; slow down and try again shortly");
            }
            if (!SAFE_METHODS.contains(method)) {
                checkOrigin(request);
            }
            if (BODY_METHODS.contains(method)) {
                checkJsonBody(request);
            }
        } catch (MeccException e) {
            writeError(response, callback, e);
            return true;
        }

        CompletableFuture<byte[]> body = BODY_METHODS.contains(method)
                ? BoundedBodyReader.read(request, security.maxBodyBytes())
                : CompletableFuture.completedFuture(new byte[0]);

        TokenSource token = presentedToken(request);
        body.handle((bytes, error) -> {
                    if (error != null) {
                        throw new CompletionException(new MeccException(ErrorCode.PAYLOAD_TOO_LARGE,
                                "Request body is unreadable or larger than " + security.maxBodyBytes() + " bytes"));
                    }
                    return bytes;
                })
                .thenCompose(bytes -> authenticate(route, token, client)
                        .thenApply(session -> limitWrites(method, session))
                        .thenCompose(session -> invoke(route, new ApiRequest(method, path, request.getHttpURI().getQuery(),
                                match.parameters(), bytes, client, session.orElse(null), json,
                                header -> request.getHeaders().get(header)))
                                .thenApply(result -> new Outcome(result, session.orElse(null)))))
                .whenComplete((outcome, error) -> {
                    if (error != null) {
                        MeccException unauthenticated = asMecc(error, ErrorCode.UNAUTHENTICATED);
                        if (unauthenticated != null && token.fromCookie()) {
                            // A stale or revoked cookie: remove it so the browser returns to pairing.
                            Response.addCookie(response, deviceCookie("", 0, secure));
                        }
                        writeError(response, callback, error);
                        return;
                    }
                    writeOutcome(request, response, callback, outcome, token, secure);
                });
        return true;
    }

    private record Outcome(Object result, Session session) {
    }

    private record TokenSource(String token, boolean fromCookie) {
    }

    private CompletionStage<Optional<Session>> authenticate(Route route, TokenSource token, ClientInfo client) {
        if (route.access() == Access.PUBLIC) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        if (token.token() == null) {
            return CompletableFuture.failedFuture(new MeccException(ErrorCode.UNAUTHENTICATED,
                    "Sign in by pairing this browser first"));
        }
        return sessions.resolve(token.token(), client).thenApply(session -> {
            if (session.isEmpty()) {
                throw new CompletionException(new MeccException(ErrorCode.UNAUTHENTICATED,
                        "This device is not paired or was revoked"));
            }
            return session;
        });
    }

    private Optional<Session> limitWrites(String method, Optional<Session> session) {
        if (session.isPresent() && !SAFE_METHODS.contains(method)
                && !limits.allowWrite(session.get().user().playerUuid())) {
            throw new CompletionException(new MeccException(ErrorCode.RATE_LIMITED,
                    "Too many changes in a short time; wait a moment and try again"));
        }
        return session;
    }

    private static CompletionStage<?> invoke(Route route, ApiRequest request) {
        try {
            CompletionStage<?> stage = route.endpoint().handle(request);
            return stage == null ? CompletableFuture.completedFuture(null) : stage;
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }

    private void writeOutcome(Request request, Response response, Callback callback, Outcome outcome, TokenSource token,
                              boolean secure) {
        if (outcome.result() instanceof ApiResponse apiResponse) {
            if (apiResponse.deviceToken() != null) {
                Response.addCookie(response, deviceCookie(apiResponse.deviceToken(), DEVICE_COOKIE_MAX_AGE_SECONDS, secure));
            } else if (apiResponse.clearDeviceToken()) {
                Response.addCookie(response, deviceCookie("", 0, secure));
            }
            if (apiResponse.binaryContent() != null) {
                writeBinary(request, response, callback, apiResponse.binaryContent());
                return;
            }
            write(response, callback, apiResponse.status(), apiResponse.body());
            return;
        }
        if (outcome.session() != null && outcome.session().renewCookie() && token.fromCookie()) {
            Response.addCookie(response, deviceCookie(token.token(), DEVICE_COOKIE_MAX_AGE_SECONDS, secure));
        }
        write(response, callback, 200, outcome.result());
    }

    /**
     * CSRF defense in depth (the cookie is also SameSite=Strict): state-changing requests must not come
     * from a foreign browser origin.
     */
    private void checkOrigin(Request request) {
        if (!RequestTrust.originAllowed(request, security.allowedOrigins())) {
            throw new MeccException(ErrorCode.ORIGIN_REJECTED, "Requests from this origin are not allowed");
        }
    }

    private void checkJsonBody(Request request) {
        long length = request.getLength();
        if (length > security.maxBodyBytes()) {
            throw new MeccException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "Request body is larger than " + security.maxBodyBytes() + " bytes");
        }
        String contentType = request.getHeaders().get(HttpHeader.CONTENT_TYPE);
        if (length == 0 && contentType == null) {
            // Forms cannot send a bodiless POST with a custom type, but they can send an empty one:
            // require an explicit JSON type anyway so every state change is a CORS-preflighted request.
            throw new MeccException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Content-Type must be application/json");
        }
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw new MeccException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Content-Type must be application/json");
        }
    }

    private static TokenSource presentedToken(Request request) {
        RequestTrust.PresentedToken presented = RequestTrust.presentedToken(request);
        return new TokenSource(presented.token(), presented.fromCookie());
    }

    private static HttpCookie deviceCookie(String value, long maxAgeSeconds, boolean secure) {
        return HttpCookie.build(DEVICE_COOKIE, value)
                .path("/")
                .httpOnly(true)
                .secure(secure)
                .sameSite(HttpCookie.SameSite.STRICT)
                .maxAge(maxAgeSeconds)
                .build();
    }

    private void writeError(Response response, Callback callback, Throwable error) {
        Throwable cause = unwrap(error);
        ErrorResponse body;
        int status;
        if (cause instanceof MeccException meccException) {
            status = meccException.code().httpStatus();
            body = ErrorResponse.of(meccException.code(), meccException.getMessage(), meccException.details());
        } else {
            LOGGER.error("Unhandled error in ME Control Center API", cause);
            status = ErrorCode.INTERNAL_ERROR.httpStatus();
            body = ErrorResponse.of(ErrorCode.INTERNAL_ERROR, "Internal server error");
        }
        write(response, callback, status, body);
    }

    private static void writeBinary(Request request, Response response, Callback callback, ApiResponse.Binary content) {
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, content.contentType());
        response.getHeaders().put(HttpHeader.CACHE_CONTROL, content.cacheControl());
        if (content.etag() != null) {
            response.getHeaders().put(HttpHeader.ETAG, content.etag());
            String ifNoneMatch = request.getHeaders().get(HttpHeader.IF_NONE_MATCH);
            if (ifNoneMatch != null && ifNoneMatch.contains(content.etag())) {
                response.setStatus(304);
                response.write(true, BufferUtil.EMPTY_BUFFER, callback);
                return;
            }
        }
        response.setStatus(200);
        response.getHeaders().put(HttpHeader.CONTENT_LENGTH, content.content().length);
        response.write(true, ByteBuffer.wrap(content.content()).asReadOnlyBuffer(), callback);
    }

    private void write(Response response, Callback callback, int status, Object body) {
        byte[] bytes;
        if (status == 204) {
            bytes = new byte[0];
        } else {
            try {
                bytes = json.toBytes(body);
            } catch (Exception e) {
                LOGGER.error("Failed to serialize ME Control Center API response", e);
                status = ErrorCode.INTERNAL_ERROR.httpStatus();
                bytes = "{\"error\":{\"code\":\"INTERNAL_ERROR\",\"message\":\"Internal server error\",\"details\":{}}}"
                        .getBytes(StandardCharsets.UTF_8);
            }
        }
        response.setStatus(status);
        if (bytes.length > 0) {
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, JSON);
        }
        response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store");
        response.getHeaders().put(HttpHeader.CONTENT_LENGTH, bytes.length);
        response.write(true, ByteBuffer.wrap(bytes), callback);
    }

    private static MeccException asMecc(Throwable error, ErrorCode code) {
        Throwable cause = unwrap(error);
        return cause instanceof MeccException e && e.code() == code ? e : null;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
