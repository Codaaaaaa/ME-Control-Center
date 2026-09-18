package io.github.codaaaaaa.mecc.core.error;

import java.util.Map;
import java.util.Objects;

/**
 * An expected failure carrying a stable {@link ErrorCode}. Its message and details are safe to show
 * to users; never put secrets, tokens, or stack traces in them.
 */
public class MeccException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final transient Map<String, Object> details;

    public MeccException(ErrorCode code, String message) {
        this(code, message, Map.of(), null);
    }

    public MeccException(ErrorCode code, String message, Throwable cause) {
        this(code, message, Map.of(), cause);
    }

    public MeccException(ErrorCode code, String message, Map<String, Object> details) {
        this(code, message, details, null);
    }

    public MeccException(ErrorCode code, String message, Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.details = Map.copyOf(details);
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static MeccException validation(String field, String message) {
        return new MeccException(ErrorCode.VALIDATION_FAILED, message, Map.of("field", field));
    }
}
