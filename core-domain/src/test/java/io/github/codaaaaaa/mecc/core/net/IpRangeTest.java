package io.github.codaaaaaa.mecc.core.net;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IpRangeTest {

    @Test
    void matchesSingleAddressesAndCidrBlocks() {
        IpRange loopback = IpRange.parse("127.0.0.1").orElseThrow();
        assertTrue(loopback.contains("127.0.0.1"));
        assertFalse(loopback.contains("127.0.0.2"));

        IpRange privateBlock = IpRange.parse("10.0.0.0/8").orElseThrow();
        assertTrue(privateBlock.contains("10.200.3.4"));
        assertFalse(privateBlock.contains("11.0.0.1"));

        IpRange odd = IpRange.parse("192.168.1.128/25").orElseThrow();
        assertTrue(odd.contains("192.168.1.200"));
        assertFalse(odd.contains("192.168.1.127"));

        IpRange v6 = IpRange.parse("fd00::/8").orElseThrow();
        assertTrue(v6.contains("[fd12::1]"));
        assertFalse(v6.contains("10.0.0.1"));
    }

    @Test
    void rejectsHostNamesAndGarbageWithoutDns() {
        assertTrue(IpRange.parse("example.org").isEmpty());
        assertTrue(IpRange.parse("localhost").isEmpty());
        assertTrue(IpRange.parse("10.0.0.0/33").isEmpty());
        assertTrue(IpRange.parse("").isEmpty());
        assertFalse(IpRange.parse("127.0.0.1").orElseThrow().contains("not-an-ip"));
        // Hex-only words are valid host names; they must be rejected, not resolved.
        assertTrue(IpRange.parseLiteral("cafe").isEmpty());
        assertTrue(IpRange.parseLiteral("deadbeef").isEmpty());
        assertTrue(IpRange.parseLiteral("1.2.3").isEmpty());
        assertTrue(IpRange.parseLiteral("::ffff:10.0.0.1").isPresent());
    }
}
