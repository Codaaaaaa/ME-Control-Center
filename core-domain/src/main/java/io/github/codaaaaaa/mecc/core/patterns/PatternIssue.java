package io.github.codaaaaaa.mecc.core.patterns;

import java.util.Objects;

/**
 * Why a pattern definition cannot be encoded. Codes are part of the API contract.
 *
 * @param code    e.g. {@code NO_INPUTS}, {@code NOT_AN_ITEM}, {@code NO_MATCHING_RECIPE}
 * @param field   the offending part, e.g. {@code inputs[4]}, or {@code null} for the whole pattern
 * @param message English explanation; the UI translates by code
 */
public record PatternIssue(String code, String field, String message) {
    public static final String NO_INPUTS = "NO_INPUTS";
    public static final String WRONG_SLOT_COUNT = "WRONG_SLOT_COUNT";
    public static final String TOO_MANY_INPUTS = "TOO_MANY_INPUTS";
    public static final String TOO_MANY_OUTPUTS = "TOO_MANY_OUTPUTS";
    public static final String MISSING_PRIMARY_OUTPUT = "MISSING_PRIMARY_OUTPUT";
    public static final String MISSING_INPUT = "MISSING_INPUT";
    public static final String NOT_AN_ITEM = "NOT_AN_ITEM";
    public static final String AMOUNT_NOT_ONE = "AMOUNT_NOT_ONE";
    public static final String AMOUNT_TOO_LARGE = "AMOUNT_TOO_LARGE";
    public static final String RECIPE_REQUIRED = "RECIPE_REQUIRED";
    public static final String INVALID_RECIPE_ID = "INVALID_RECIPE_ID";
    public static final String UNKNOWN_RESOURCE = "UNKNOWN_RESOURCE";
    public static final String UNSUPPORTED_RESOURCE_TYPE = "UNSUPPORTED_RESOURCE_TYPE";
    public static final String NO_MATCHING_RECIPE = "NO_MATCHING_RECIPE";
    public static final String NOT_ENCODABLE = "NOT_ENCODABLE";

    public PatternIssue {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
