package io.github.codaaaaaa.mecc.assets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Translation tables merged from {@code assets/<namespace>/lang/<locale>.json} of all packs
 * (spec section 40, source 2). Loaded lazily per locale; thread-safe.
 */
public final class LanguageTables {
    private static final Logger LOGGER = LoggerFactory.getLogger(LanguageTables.class);
    private static final Pattern LOCALE = Pattern.compile("^[a-z]{2,3}_[a-z]{2,3}$");

    private final AssetLibrary library;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Map<String, String>> tables = new ConcurrentHashMap<>();

    public LanguageTables(AssetLibrary library) {
        this.library = library;
    }

    public Optional<String> translate(String locale, String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(table(locale).get(key));
    }

    /** All translations for a locale; empty for malformed locale codes. */
    public Map<String, String> table(String locale) {
        if (locale == null || !LOCALE.matcher(locale).matches()) {
            return Map.of();
        }
        return tables.computeIfAbsent(locale, this::load);
    }

    private Map<String, String> load(String locale) {
        Map<String, String> table = new HashMap<>();
        for (String namespace : library.namespaces()) {
            for (byte[] bytes : library.readAll(namespace, "lang/" + locale + ".json")) {
                try {
                    JsonNode root = json.readTree(bytes);
                    if (root == null || !root.isObject()) {
                        continue;
                    }
                    for (Map.Entry<String, JsonNode> entry : root.properties()) {
                        if (entry.getValue().isTextual()) {
                            table.put(entry.getKey(), entry.getValue().textValue());
                        }
                    }
                } catch (Exception e) {
                    LOGGER.debug("Ignoring malformed language file {}:lang/{}.json: {}", namespace, locale, e.toString());
                }
            }
        }
        LOGGER.debug("Loaded {} {} translations", table.size(), locale);
        return Map.copyOf(table);
    }
}
