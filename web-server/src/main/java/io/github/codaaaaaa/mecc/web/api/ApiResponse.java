package io.github.codaaaaaa.mecc.web.api;

/**
 * An endpoint result with a non-default status, binary content, or cookie side effects. Endpoints that
 * simply return a value produce {@code 200} with that value as JSON.
 */
public final class ApiResponse {
    private final int status;
    private final Object body;
    private final Binary binary;
    private final String deviceToken;
    private final boolean clearDeviceToken;

    /**
     * @param etag         strong entity tag; the handler answers {@code 304} when it matches
     * @param cacheControl {@code Cache-Control} header value
     */
    public record Binary(byte[] content, String contentType, String etag, String cacheControl) {
    }

    private ApiResponse(int status, Object body, Binary binary, String deviceToken, boolean clearDeviceToken) {
        this.status = status;
        this.body = body;
        this.binary = binary;
        this.deviceToken = deviceToken;
        this.clearDeviceToken = clearDeviceToken;
    }

    public static ApiResponse ok(Object body) {
        return new ApiResponse(200, body, null, null, false);
    }

    public static ApiResponse created(Object body) {
        return new ApiResponse(201, body, null, null, false);
    }

    public static ApiResponse noContent() {
        return new ApiResponse(204, null, null, null, false);
    }

    /** Binary content such as a rendered icon. */
    public static ApiResponse binary(byte[] content, String contentType, String etag, String cacheControl) {
        return new ApiResponse(200, null, new Binary(content, contentType, etag, cacheControl), null, false);
    }

    /** Sets the HttpOnly device cookie. */
    public ApiResponse withDeviceToken(String token) {
        return new ApiResponse(status, body, binary, token, false);
    }

    /** Expires the device cookie. */
    public ApiResponse clearingDeviceToken() {
        return new ApiResponse(status, body, binary, null, true);
    }

    public int status() {
        return status;
    }

    public Object body() {
        return body;
    }

    public Binary binaryContent() {
        return binary;
    }

    public String deviceToken() {
        return deviceToken;
    }

    public boolean clearDeviceToken() {
        return clearDeviceToken;
    }
}
