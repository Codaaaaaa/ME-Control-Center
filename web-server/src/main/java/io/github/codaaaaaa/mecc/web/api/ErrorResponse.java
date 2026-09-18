package io.github.codaaaaaa.mecc.web.api;

import io.github.codaaaaaa.mecc.core.error.ErrorCode;
import java.util.Map;

/** Structured API error envelope: {@code {"error":{"code":"...","message":"...","details":{}}}}. */
public record ErrorResponse(Body error) {

    public record Body(String code, String message, Map<String, Object> details) {
    }

    public static ErrorResponse of(ErrorCode code, String message) {
        return of(code, message, Map.of());
    }

    public static ErrorResponse of(ErrorCode code, String message, Map<String, Object> details) {
        return new ErrorResponse(new Body(code.name(), message, details));
    }
}
