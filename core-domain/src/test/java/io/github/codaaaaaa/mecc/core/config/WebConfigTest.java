package io.github.codaaaaaa.mecc.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WebConfigTest {

    @Test
    void defaultsAreValid() {
        assertTrue(MeccConfig.defaults().validate().isEmpty());
        assertEquals("127.0.0.1", WebConfig.defaults().host());
        assertEquals(18181, WebConfig.defaults().port());
    }

    @Test
    void reportsEveryProblem() {
        var problems = new WebConfig(true, "bad host!", 0, 4, "").validate();
        assertEquals(3, problems.size(), problems.toString());
    }

    @Test
    void acceptsWildcardAndIpv6Hosts() {
        assertTrue(new WebConfig(true, "0.0.0.0", 18181, 32, "").validate().isEmpty());
        assertTrue(new WebConfig(true, "::", 18181, 32, "").validate().isEmpty());
        assertTrue(new WebConfig(true, "mc.example.org", 65535, 256, "https://mecc.example.org").validate().isEmpty());
    }
}
