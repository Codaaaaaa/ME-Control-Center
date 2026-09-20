package io.github.codaaaaaa.mecc.runtime.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import io.github.codaaaaaa.mecc.core.config.AlertsConfig;
import io.github.codaaaaaa.mecc.core.config.AnalyticsConfig;
import io.github.codaaaaaa.mecc.core.config.AssetsConfig;
import io.github.codaaaaaa.mecc.core.config.AutomationConfig;
import io.github.codaaaaaa.mecc.core.config.ConfigValidationException;
import io.github.codaaaaaa.mecc.core.config.CraftingConfig;
import io.github.codaaaaaa.mecc.core.config.MeccConfig;
import io.github.codaaaaaa.mecc.core.config.NetworksConfig;
import io.github.codaaaaaa.mecc.core.config.PatternsConfig;
import io.github.codaaaaaa.mecc.core.config.ResourcesConfig;
import io.github.codaaaaaa.mecc.core.config.SecurityConfig;
import io.github.codaaaaaa.mecc.core.config.WebConfig;
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

    private static final Map<String, Set<String>> KNOWN_KEYS = Map.ofEntries(
            Map.entry("web", Set.of("enabled", "host", "port", "max_threads", "public_base_url")),
            Map.entry("security", Set.of("pairing_key_ttl_seconds", "trusted_proxies", "admin_override", "admin_op_level",
                    "require_https_cookie", "allowed_origins", "rate_limit_requests_per_minute",
                    "rate_limit_writes_per_minute")),
            Map.entry("networks", Set.of("discovery_interval_seconds")),
            Map.entry("resources", Set.of("snapshot_max_age_seconds")),
            Map.entry("assets", Set.of("download_vanilla_assets")),
            Map.entry("crafting", Set.of("max_craft_amount", "calculation_timeout_seconds")),
            Map.entry("patterns", Set.of("max_pattern_inputs", "max_pattern_outputs", "max_drafts_per_user")),
            Map.entry("analytics", Set.of("enabled", "sample_interval_seconds", "raw_retention_hours",
                    "one_minute_retention_days", "five_minute_retention_days", "one_hour_retention_days",
                    "max_watchlist_entries_per_user")),
            Map.entry("alerts", Set.of("enabled", "check_interval_seconds", "webhooks_enabled",
                    "allow_private_webhook_targets", "max_rules_per_user")),
            Map.entry("automation", Set.of("auto_restock_enabled", "check_interval_seconds",
                    "max_active_jobs_per_network", "max_rules_per_network")));

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

            # Rate limits. Every API request and WebSocket connection counts against the client's address
            # (behind a reverse proxy: the forwarded address, see trusted_proxies); icons are exempt.
            # Changes (crafting, patterns, sharing, ...) additionally count against the player.
            rate_limit_requests_per_minute = 1200
            rate_limit_writes_per_minute = 120

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

            [patterns]
            # Most inputs and outputs of one processing pattern (1-81 and 1-27, AE2's own limits).
            max_pattern_inputs = 81
            max_pattern_outputs = 27

            # Pattern drafts one player may keep in Pattern Studio (1-10000).
            max_drafts_per_user = 200

            [analytics]
            # Record the history of watched resources. One storage snapshot per watched network and interval,
            # however many players watch how many resources.
            enabled = true
            sample_interval_seconds = 15

            # How long each resolution is kept. Charts pick the resolution that fits the range shown.
            raw_retention_hours = 24
            one_minute_retention_days = 7
            five_minute_retention_days = 90
            one_hour_retention_days = 730

            # Watchlist entries one player may keep across all networks (1-1000).
            max_watchlist_entries_per_user = 100

            [alerts]
            # Players' alert rules: low or high stock, network offline, low energy, all CPUs busy, crafts done/failed.
            enabled = true
            # How often rules are checked, in seconds (5-300). Uses the same storage snapshots as the terminal.
            check_interval_seconds = 15
            # Let players send their alerts to a Discord webhook or a generic JSON webhook.
            webhooks_enabled = true
            # Webhooks may not reach loopback or private (LAN) addresses unless this is true. Keep it false on
            # public servers: otherwise any player could make the server send requests into its own network.
            allow_private_webhook_targets = false
            # Alert rules one player may keep across all networks (1-1000).
            max_rules_per_user = 50

            [automation]
            # Auto Restock / Keep Stock (spec section 25): ME Control Center may craft a resource back up to a
            # target when its stored amount falls below a minimum. OFF by default, and never enabled by an update:
            # a server admin turns it on here, and a network Manager still has to create each rule.
            auto_restock_enabled = false
            # How often rules are compared against stock, in seconds (15-3600).
            check_interval_seconds = 60
            # Automation jobs that may run at the same time on one network (1-64). Jobs a player submitted do not
            # count; rules never queue a second job for a resource that is already being crafted.
            max_active_jobs_per_network = 2
            # Restock rules one network may have (1-500).
            max_rules_per_network = 50
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
        Section patterns = section(root, "patterns", problems);
        Section analytics = section(root, "analytics", problems);
        Section alerts = section(root, "alerts", problems);
        Section automation = section(root, "automation", problems);

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
                security.stringList("allowed_origins", securityDefaults.allowedOrigins()),
                security.integer("rate_limit_requests_per_minute", securityDefaults.rateLimitRequestsPerMinute()),
                security.integer("rate_limit_writes_per_minute", securityDefaults.rateLimitWritesPerMinute()));

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

        PatternsConfig patternsDefaults = PatternsConfig.defaults();
        PatternsConfig patternsConfig = new PatternsConfig(
                patterns.integer("max_pattern_inputs", patternsDefaults.maxPatternInputs()),
                patterns.integer("max_pattern_outputs", patternsDefaults.maxPatternOutputs()),
                patterns.integer("max_drafts_per_user", patternsDefaults.maxDraftsPerUser()));

        AnalyticsConfig analyticsDefaults = AnalyticsConfig.defaults();
        AnalyticsConfig analyticsConfig = new AnalyticsConfig(
                analytics.bool("enabled", analyticsDefaults.enabled()),
                analytics.integer("sample_interval_seconds", analyticsDefaults.sampleIntervalSeconds()),
                analytics.integer("raw_retention_hours", analyticsDefaults.rawRetentionHours()),
                analytics.integer("one_minute_retention_days", analyticsDefaults.oneMinuteRetentionDays()),
                analytics.integer("five_minute_retention_days", analyticsDefaults.fiveMinuteRetentionDays()),
                analytics.integer("one_hour_retention_days", analyticsDefaults.oneHourRetentionDays()),
                analytics.integer("max_watchlist_entries_per_user", analyticsDefaults.maxWatchlistEntriesPerUser()));

        AlertsConfig alertsDefaults = AlertsConfig.defaults();
        AlertsConfig alertsConfig = new AlertsConfig(
                alerts.bool("enabled", alertsDefaults.enabled()),
                alerts.integer("check_interval_seconds", alertsDefaults.checkIntervalSeconds()),
                alerts.bool("webhooks_enabled", alertsDefaults.webhooksEnabled()),
                alerts.bool("allow_private_webhook_targets", alertsDefaults.allowPrivateWebhookTargets()),
                alerts.integer("max_rules_per_user", alertsDefaults.maxRulesPerUser()));

        AutomationConfig automationDefaults = AutomationConfig.defaults();
        AutomationConfig automationConfig = new AutomationConfig(
                automation.bool("auto_restock_enabled", automationDefaults.autoRestockEnabled()),
                automation.integer("check_interval_seconds", automationDefaults.checkIntervalSeconds()),
                automation.integer("max_active_jobs_per_network", automationDefaults.maxActiveJobsPerNetwork()),
                automation.integer("max_rules_per_network", automationDefaults.maxRulesPerNetwork()));

        MeccConfig config = new MeccConfig(new WebConfig(enabled, host, port, maxThreads, publicBaseUrl),
                securityConfig, networksConfig, resourcesConfig, assetsConfig, craftingConfig, patternsConfig,
                analyticsConfig, alertsConfig, automationConfig);
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
