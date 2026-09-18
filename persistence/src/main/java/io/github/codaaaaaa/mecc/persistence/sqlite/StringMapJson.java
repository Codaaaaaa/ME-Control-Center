package io.github.codaaaaaa.mecc.persistence.sqlite;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * JSON encoding of flat string maps, so audit parameters stay queryable with SQLite's JSON functions
 * without pulling a JSON library into the persistence module.
 */
final class StringMapJson {
    private StringMapJson() {
    }

    static String write(Map<String, String> map) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : new TreeMap<>(map).entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            quote(json, entry.getKey());
            json.append(':');
            if (entry.getValue() == null) {
                json.append("null");
            } else {
                quote(json, entry.getValue());
            }
        }
        return json.append('}').toString();
    }

    static Map<String, String> read(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        if (json == null) {
            return result;
        }
        Parser parser = new Parser(json);
        parser.expect('{');
        if (parser.peek() == '}') {
            return result;
        }
        do {
            String key = parser.string();
            parser.expect(':');
            String value = parser.peek() == 'n' ? parser.nullLiteral() : parser.string();
            result.put(key, value);
        } while (parser.tryConsume(','));
        parser.expect('}');
        return result;
    }

    private static void quote(StringBuilder json, String value) {
        json.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        json.append('"');
    }

    private static final class Parser {
        private final String text;
        private int position;

        Parser(String text) {
            this.text = text;
        }

        char peek() {
            skipWhitespace();
            if (position >= text.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            return text.charAt(position);
        }

        void expect(char c) {
            if (peek() != c) {
                throw new IllegalArgumentException("Expected '" + c + "' at " + position);
            }
            position++;
        }

        boolean tryConsume(char c) {
            if (peek() == c) {
                position++;
                return true;
            }
            return false;
        }

        String nullLiteral() {
            skipWhitespace();
            if (!text.startsWith("null", position)) {
                throw new IllegalArgumentException("Expected null at " + position);
            }
            position += 4;
            return null;
        }

        String string() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (position < text.length()) {
                char c = text.charAt(position++);
                if (c == '"') {
                    return value.toString();
                }
                if (c != '\\') {
                    value.append(c);
                    continue;
                }
                char escaped = text.charAt(position++);
                switch (escaped) {
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> {
                        value.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                        position += 4;
                    }
                    default -> value.append(escaped);
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        private void skipWhitespace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }
    }
}
