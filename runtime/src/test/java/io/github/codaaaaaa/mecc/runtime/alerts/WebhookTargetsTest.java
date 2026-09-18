package io.github.codaaaaaa.mecc.runtime.alerts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.codaaaaaa.mecc.core.error.MeccException;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class WebhookTargetsTest {

    @Test
    void discordWebhooksMustPointAtDiscord() {
        assertEquals("https://discord.com/api/webhooks/1/abc", WebhookTargets.discord(" https://discord.com/api/webhooks/1/abc "));
        assertNull(WebhookTargets.discord("  "));
        assertThrows(MeccException.class, () -> WebhookTargets.discord("http://discord.com/api/webhooks/1/abc"));
        assertThrows(MeccException.class, () -> WebhookTargets.discord("https://discord.com.evil.org/api/webhooks/1/abc"));
        assertThrows(MeccException.class, () -> WebhookTargets.discord("https://discord.com:8443/api/webhooks/1/abc"));
        assertThrows(MeccException.class, () -> WebhookTargets.discord("https://discord.com/channels/1"));
    }

    @Test
    void genericWebhooksAreHttpUrlsWithoutCredentials() {
        assertEquals("https://example.org/hook", WebhookTargets.generic("https://example.org/hook"));
        assertThrows(MeccException.class, () -> WebhookTargets.generic("file:///etc/passwd"));
        assertThrows(MeccException.class, () -> WebhookTargets.generic("https://user:pw@example.org/hook"));
        assertThrows(MeccException.class, () -> WebhookTargets.generic("https://example.org/" + "x".repeat(600)));
    }

    @Test
    void internalAddressesAreRefused() throws Exception {
        for (String internal : new String[] {"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254",
                "100.64.0.1", "0.0.0.0", "::1", "fd00::1", "fe80::1", "::ffff:127.0.0.1"}) {
            assertTrue(WebhookTargets.internal(InetAddress.getByName(internal)), internal);
        }
        assertFalse(WebhookTargets.internal(InetAddress.getByName("1.1.1.1")));
        assertFalse(WebhookTargets.internal(InetAddress.getByName("2606:4700:4700::1111")));
    }
}
