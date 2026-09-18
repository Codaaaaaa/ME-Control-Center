package io.github.codaaaaaa.mecc.core.text;

/** Normalization of user-entered display names (device names, network names). */
public final class DisplayText {
    private DisplayText() {
    }

    /**
     * Trims, collapses runs of whitespace to one space, drops control characters, and truncates to
     * {@code maxLength} characters. Returns an empty string for {@code null}.
     */
    public static String sanitize(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        StringBuilder clean = new StringBuilder();
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                pendingSpace = clean.length() > 0;
                continue;
            }
            if (Character.isISOControl(c)) {
                continue;
            }
            if (pendingSpace) {
                clean.append(' ');
                pendingSpace = false;
            }
            clean.append(c);
        }
        String result = clean.toString();
        if (result.codePointCount(0, result.length()) > maxLength) {
            result = result.substring(0, result.offsetByCodePoints(0, maxLength)).strip();
        }
        return result;
    }
}
