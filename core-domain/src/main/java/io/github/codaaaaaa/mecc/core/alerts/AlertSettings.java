package io.github.codaaaaaa.mecc.core.alerts;

import java.util.Objects;
import java.util.UUID;

/**
 * Where a player's alerts go besides the web UI (spec section 23).
 *
 * @param discordWebhookUrl Discord webhook, or {@code null}
 * @param webhookUrl        generic JSON webhook, or {@code null}
 * @param locale            language of webhook messages
 */
public record AlertSettings(UUID playerUuid, String discordWebhookUrl, String webhookUrl, String locale) {
    public AlertSettings {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(locale, "locale");
    }
}
