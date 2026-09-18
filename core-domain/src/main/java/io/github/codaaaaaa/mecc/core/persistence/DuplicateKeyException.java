package io.github.codaaaaaa.mecc.core.persistence;

/** A unique constraint was violated. */
public final class DuplicateKeyException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DuplicateKeyException(String message, Throwable cause) {
        super(message, cause);
    }
}
