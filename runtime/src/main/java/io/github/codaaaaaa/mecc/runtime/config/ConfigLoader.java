package io.github.codaaaaaa.mecc.runtime.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import io.github.codaaaaaa.mecc.core.config.AssetsConfig;
import io.github.codaaaaaa.mecc.core.config.ConfigValidationException;
import io.github.codaaaaaa.mecc.core.config.CraftingConfig;
import io.github.codaaaaaa.mecc.core.config.NetworksConfig;
import io.github.codaaaaaa.mecc.core.config.ResourcesConfig;
import io.github.codaaaaaa.mecc.core.config.SecurityConfig;
import io.github.codaaaaaa.mecc.core.config.WebConfig;
import io.github.codaaaaaa.mecc.core.config.MeccConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads {@code mecc.toml}, creating a commented default file on first start. Missing sections and keys
 * fall back to defaults, so files written by older ME Control Center versions keep working.
 *
 * <p>System properties {@value #HOST_PROPERTY} and {@value #PORT_PROPERTY} override the file,
 * which is convenient for development run configurations and containers.
 */
public final class ConfigLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigLoader.class);

    public static final String FILE_NAME = "mecc.toml";
    public static final String HOST_PROPERTY = "mecc.web.host";
    public static final String PORT_PROPERTY = "mecc.web.port";

    private static final Map<String, Set<String>> KNOWN_KEYS = Map.of(
            "web", Set.of("enabled", "host", "port", "max_threads", "public_base_url"),
            "security", Set.of("pairing_key_ttl_seconds", "trusted_proxies", "admin_override", "admin_op_level",
                    "require_https_cookie", "allowed_origins"),
            "networks", Set.of("discovery_interval_seconds"),
            "resources", Set.of("snapshot_max_age_seconds"),
            "assets", Set.of("download_vanilla_assets"),
            "crafting", Set.of("max_craft_amount", "calculation_timeout_seconds"));

    static final String DEFAULT_FILE = """
            # ME Control Center configuration.
            # Changes take effect after a server restart.

            [web]
            # Set to false to disable the embedded web server entirely.
            enabled = true

            # Network interface to bind.
            #   "127.0.0.1" accepts connections from this machine only (default, safest).
            #   "0.0.0.0"   accepts connections from other devices: http://<server-ip>:<port>/
            # For internet exposure, put ME Control Center behind an HTTPS reverse proxy (Nginx, Caddy, Traefik).
            host = "127.0.0.1"

            # TCP port of the web UI and API.
            port = 18181

            # Maximum number of HTTP worker threads (8-256).
            max_threads = 32

            # The address players open in their browser, e.g. "https://mecc.example.org".
            # Shown by /mecc pair. Leave empty if unknown.
            public_base_url = ""

            [security]
            # Lifetime of a /mecc pair key, in seconds (30-3600).
            pairing_key_ttl_seconds = 300

            # Reverse proxies whose X-Forwarded-For / X-Forwarded-Proto headers are trusted,
            # as IP addresses or CIDR blocks, e.g. ["127.0.0.1", "10.0.0.0/8"].
            # Leave empty when ME Control Center is reached directly: forwarding headers are then ignored.
            trusted_proxies = []

            # Server admins (operators at admin_op_level or above) get Owner access to every enrolled network.
            # Actions that rely on this are recorded in the audit log.
            admin_override = true
            admin_op_level = 4

            # Always mark the login cookie Secure. Enable when ME Control Center is only reachable over HTTPS.
            require_https_cookie = false

            # Extra browser origins allowed to make changes, e.g. ["https://mecc.example.org"].
            # The origin of public_base_url and the address the browser used are always allowed.
            allowed_origins = []

            [networks]
            # How often loaded ME networks are discovered and their status refreshed, in seconds (2-300).
            discovery_interval_seconds = 10

            [resources]
            # A network's storage is read at most this often (1-60 seconds), however many browsers are open.
            snapshot_max_age_seconds = 5

            [assets]
            # Dedicated servers do not include vanilla Minecraft textures and translations, so vanilla items
            # show placeholder icons. Set to true to download them once from Mojang's official servers
            # (about 25 MB, cached in config/mecc/cache/vanilla/). By enabling this you accept Mojang's
            # terms for these files. Alternatively place client.jar there yourself.
            # Resource packs (folders or .zip) in config/mecc/resourcepacks/ are also used for icons and names.
            download_vanilla_assets = false

            [crafting]
            # Largest amount a single crafting request from the web UI may ask for.
            max_craft_amount = 1000000000

            # A crafting calculation still running after this many seconds is abandoned (5-600).
            calculation_timeout_seconds = 60
            """;

    private final Path file;
    private final UnaryOperator<String> properties;

    public ConfigLoader(Path configDirectory) {
        this(configDirectory, System::getProperty);
    }

    ConfigLoader(Path configDirectory, UnaryOperator<String> properties) {
        this.file = configDirectory.resolve(FILE_NAME);
        this.properties = properties;
    }

    public Path file() {
        return file;
    }

    public MeccConfig load() throws IOException, ConfigValidationException {
        if (Files.notExists(file)) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, DEFAULT_FILE, StandardCharsets.UTF_8);
            LOGGER.info("Created default ME Control Center configuration at {}", file);
        }

        JsonNode root;
        try {
            root = new TomlMapper().readTree(file.toFile());
        } catch (IOException e) {
            throw new ConfigValidationException(file.toString(), List.of("could not parse TOML: " + e.getMessage()));
        }

        List<String> problems = new ArrayList<>();
        Section web = section(root, "web", problems);
        Section security = section(root, "security", problems);
        Section networks = section(root, "networks", problems);
        Section resources = section(root, "resources", problems);
        Section assets = section(root, "assets", problems);
        Section crafting = section(root, "crafting", problems);

        WebConfig webDefaults = WebConfig.defaults();
        boolean enabled = web.bool("enabled", webDefaults.enabled());
        String host = web.string("host", webDefaults.host());
        int port = web.integer("port", webDefaults.port());
        int maxThreads = web.integer("max_threads", webDefaults.maxThreads());
        String publicBaseUrl = web.string("public_base_url", webDefaults.publicBaseUrl());

        String hostOverride = properties.apply(HOST_PROPERTY);
        if (hostOverride != null && !hostOverride.isBlank()) {
            LOGGER.info("ME Control Center web.host overridden by system property {}={}", HOST_PROPERTY, hostOverride);
            host = hostOverride.strip();
        }
        String portOverride = properties.apply(PORT_PROPERTY);
        if (portOverride != null && !portOverride.isBlank()) {
            try {
                port = Integer.parseInt(portOverride.strip());
                LOGGER.info("ME Control Center web.port overridden by system property {}={}", PORT_PROPERTY, port);
            } catch (NumberFormatException e) {
                problems.add("system property " + PORT_PROPERTY + " is not an integer: " + portOverride);
            }
        }

        SecurityConfig securityDefaults = SecurityConfig.defaults();
        SecurityConfig securityConfig = new SecurityConfig(
                security.integer("pairing_key_ttl_seconds", securityDefaults.pairingKeyTtlSeconds()),
                security.stringList("trusted_proxies", securityDefaults.trustedProxies()),
                security.bool("admin_override", securityDefaults.adminOverride()),
                security.integer("admin_op_level", securityDefaults.adminOpLevel()),
                security.bool("require_https_cookie", securityDefaults.requireHttpsCookie()),
                security.stringList("allowed_origins", securityDefaults.allowedOrigins()));

        NetworksConfig networksConfig = new NetworksConfig(
                networks.integer("discovery_interval_seconds", NetworksConfig.defaults().discoveryIntervalSeconds()));

        ResourcesConfig resourcesConfig = new ResourcesConfig(
                resources.integer("snapshot_max_age_seconds", ResourcesConfig.defaults().snapshotMaxAgeSeconds()));
        AssetsConfig assetsConfig = new AssetsConfig(
                assets.bool("download_vanilla_assets", AssetsConfig.defaults().downloadVanillaAssets()));

        CraftingConfig craftingDefaults = CraftingConfig.defaults();
        CraftingConfig craftingConfig = new CraftingConfig(
                crafting.longValue("max_craft_amount", craftingDefaults.maxCraftAmount()),
                crafting.integer("calculation_timeout_seconds", craftingDefaults.calculationTimeoutSeconds()));

        MeccConfig config = new MeccConfig(new WebConfig(enabled, host, port, maxThreads, publicBaseUrl),
                securityConfig, networksConfig, resourcesConfig, assetsConfig, craftingConfig);
        if (problems.isEmpty()) {
            problems.addAll(config.validate());
        }
        if (!problems.isEmpty()) {
            throw new ConfigValidationException(file.toString(), problems);
        }
        return config;
    }

    private static Section section(JsonNode root, String name, List<String> problems) {
        JsonNode node = root == null ? null : root.get(name);
        if (node != null && !node.isObject()) {
            problems.add("[" + name + "] must be a table");
            node = null;
        }
        if (node != null) {
            Set<String> known = KNOWN_KEYS.get(name);
            for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
                String key = names.next();
                if (!known.contains(key)) {
                    LOGGER.warn("Ignoring unknown ME Control Center config key {}.{}", name, key);
                }
            }
        }
        return new Section(name, node, problems);
    }

    private record Section(String name, JsonNode node, List<String> problems) {

        private JsonNode get(String key) {
            return node == null ? null : node.get(key);
        }

        boolean bool(String key, boolean fallback) {
            JsonNode value = get(key);
            if (value == null) {
                return fallback;
            }
            if (!value.isBoolean()) {
                problems.add(name + "." + key + " must be true or false");
                return fallback;
            }
            return value.booleanValue();
        }

        String string(String key, String fallback) {
            JsonNode value = get(key);
            if (value == null) {
                return fallback;
            }
            if (!value.isTextual()) {
                problems.add(name + "." + key + " must be a string");
                return fallback;
            }
            return value.textValue().strip();
        }

        int integer(String key, int fallback) {
            JsonNode value = get(key);
            if (value == null) {
                return fallback;
            }
            if (!value.isIntegralNumber() || !value.canConvertToInt()) {
                problems.add(name + "." + key + " must be an integer");
                return fallback;
            }
            return value.intValue();
        }

        long longValue(String key, long fallback) {
            JsonNode value = get(key);
            if (value == null) {
                return fallback;
            }
            if (!value.isIntegralNumber() || !value.canConvertToLong()) {
                problems.add(name + "." + key + " must be an integer");
                return fallback;
            }
            return value.longValue();
        }

        List<String> stringList(String key, List<String> fallback) {
            JsonNode value = get(key);
            if (value == null) {
                return fallback;
            }
            if (!value.isArray()) {
                problems.add(name + "." + key + " must be an array of strings");
                return fallback;
            }
            List<String> result = new ArrayList<>();
            for (JsonNode element : value) {
                if (!element.isTextual()) {
                    problems.add(name + "." + key + " must be an array of strings");
                    return fallback;
                }
                result.add(element.textValue().strip());
            }
            return result;
        }
    }
}
