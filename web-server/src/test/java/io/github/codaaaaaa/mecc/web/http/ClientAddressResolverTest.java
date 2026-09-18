package io.github.codaaaaaa.mecc.web.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.codaaaaaa.mecc.core.net.IpRange;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClientAddressResolverTest {
    private final ClientAddressResolver direct = new ClientAddressResolver(List.of());
    private final ClientAddressResolver proxied = new ClientAddressResolver(List.of(
            IpRange.parse("10.0.0.0/8").orElseThrow(), IpRange.parse("127.0.0.1").orElseThrow()));

    @Test
    void ignoresForwardingHeadersFromUntrustedPeers() {
        assertEquals("198.51.100.7", direct.clientAddress("198.51.100.7", List.of("1.2.3.4")));
        assertEquals("198.51.100.7", proxied.clientAddress("198.51.100.7", List.of("1.2.3.4")));
        assertFalse(direct.isSecure(false, "198.51.100.7", "https"));
    }

    @Test
    void walksTheChainFromTheRightSkippingTrustedHops() {
        // A spoofed left-most entry is ignored: the first untrusted hop from the right wins.
        assertEquals("203.0.113.5", proxied.clientAddress("127.0.0.1", List.of("6.6.6.6, 203.0.113.5, 10.1.2.3")));
        assertEquals("203.0.113.5", proxied.clientAddress("127.0.0.1", List.of("6.6.6.6", "203.0.113.5")));
        assertEquals("10.1.2.3", proxied.clientAddress("127.0.0.1", List.of("10.1.2.3")));
        assertEquals("127.0.0.1", proxied.clientAddress("127.0.0.1", List.of()));
        assertEquals("127.0.0.1", proxied.clientAddress("127.0.0.1", List.of("garbage")));
        assertTrue(proxied.isSecure(false, "127.0.0.1", "https, http"));
    }
}
