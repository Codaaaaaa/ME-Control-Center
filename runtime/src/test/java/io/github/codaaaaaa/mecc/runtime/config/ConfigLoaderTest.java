package io.github.codaaaaaa.mecc.runtime.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.codaaaaaa.mecc.core.config.ConfigValidationException;
import io.github.codaaaaaa.mecc.core.config.SecurityConfig;
import io.github.codaaaaaa.mecc.core.config.WebConfig;
import io.github.codaaaaaa.mecc.core.config.MeccConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {

    @TempDir
    Path dir;

    @Test
    void createsDefaultFileThatLoadsAsDefaults() throws Exception {
        Path configDir = dir.resolve("config/mecc");
        MeccConfig config = new ConfigLoader(configDir, key -> null).load();

        assertTrue(Files.exists(configDir.resolve(ConfigLoader.FILE_NAME)));
        assertEquals(MeccConfig.defaults(), config);
    }

    @Test
    void readsValuesFromFile() throws Exception {
        write("[web]\nenabled = false\nhost = \"0.0.0.0\"\nport = 25580\nmax_threads = 16\n");
        assertEquals(new WebConfig(false, "0.0.0.0", 25580, 16, ""), new ConfigLoader(dir, key -> null).load().web());
    }

    @Test
    void missingKeysFallBackToDefaults() throws Exception {
        write("[web]\nport = 20000\n");
        WebConfig web = new ConfigLoader(dir, key -> null).load().web();
        assertEquals(20000, web.port());
        assertEquals(WebConfig.DEFAULT_HOST, web.host());
    }

    @Test
    void systemPropertiesOverrideFile() throws Exception {
        write("[web]\nhost = \"127.0.0.1\"\nport = 18181\n");
        Map<String, String> props = Map.of(ConfigLoader.HOST_PROPERTY, "0.0.0.0", ConfigLoader.PORT_PROPERTY, "18282");
        WebConfig web = new ConfigLoader(dir, props::get).load().web();
        assertEquals("0.0.0.0", web.host());
        assertEquals(18282, web.port());
    }

    @Test
    void reportsTypeAndRangeProblems() throws Exception {
        write("[web]\nenabled = \"yes\"\nport = \"http\"\n");
        ConfigValidationException e = assertThrows(ConfigValidationException.class,
                () -> new ConfigLoader(dir, key -> null).load());
        assertEquals(2, e.problems().size(), e.problems().toString());

        write("[web]\nport = 70000\n");
        e = assertThrows(ConfigValidationException.class, () -> new ConfigLoader(dir, key -> null).load());
        assertFalse(e.problems().isEmpty());
    }

    @Test
    void readsSecurityAndNetworkSections() throws Exception {
        write("""
                [web]
                public_base_url = "https://mecc.example.org/"
                [security]
                pairing_key_ttl_seconds = 120
                trusted_proxies = ["127.0.0.1", "10.0.0.0/8"]
                admin_override = false
                admin_op_level = 3
                require_https_cookie = true
                allowed_origins = ["https://panel.example.org"]
                rate_limit_requests_per_minute = 600
                rate_limit_writes_per_minute = 30
                [networks]
                discovery_interval_seconds = 30
                """);
        MeccConfig config = new ConfigLoader(dir, key -> null).load();

        assertEquals("https://mecc.example.org", config.web().publicOrigin());
        assertEquals(new SecurityConfig(120, List.of("127.0.0.1", "10.0.0.0/8"), false, 3, true,
                List.of("https://panel.example.org"), 600, 30), config.security());
        assertEquals(2, config.security().trustedProxyRanges().size());
        assertEquals(30, config.networks().discoveryIntervalSeconds());
    }

    @Test
    void rejectsInvalidSecurityValues() throws Exception {
        write("""
                [security]
                trusted_proxies = ["proxy.example.org"]
                admin_op_level = 9
                allowed_origins = "not-a-list"
                """);
        ConfigValidationException e = assertThrows(ConfigValidationException.class,
                () -> new ConfigLoader(dir, key -> null).load());
        assertEquals(1, e.problems().size(), "type errors are reported before range checks: " + e.problems());

        write("""
                [security]
                trusted_proxies = ["proxy.example.org"]
                admin_op_level = 9
                """);
        e = assertThrows(ConfigValidationException.class, () -> new ConfigLoader(dir, key -> null).load());
        assertEquals(2, e.problems().size(), e.problems().toString());
    }

    @Test
    void reportsSyntaxErrors() throws Exception {
        write("[web\nport = ");
        assertThrows(ConfigValidationException.class, () -> new ConfigLoader(dir, key -> null).load());
    }

    private void write(String toml) throws Exception {
        Files.writeString(dir.resolve(ConfigLoader.FILE_NAME), toml);
    }
}
