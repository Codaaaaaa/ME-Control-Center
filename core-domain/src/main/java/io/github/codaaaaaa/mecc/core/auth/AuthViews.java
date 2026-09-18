package io.github.codaaaaaa.mecc.core.auth;

import java.time.Instant;
import java.util.UUID;

/** API response models for authentication and devices. */
public final class AuthViews {
    private AuthViews() {
    }

    public record UserView(UUID playerUuid, String playerName) {
    }

    public record DeviceView(
            String id,
            String name,
            Instant createdAt,
            Instant lastUsedAt,
            String lastAddress,
            boolean current) {

        public static DeviceView of(Device device, String currentDeviceId) {
            return new DeviceView(device.id(), device.name(), device.createdAt(), device.lastUsedAt(),
                    device.lastAddress(), device.id().equals(currentDeviceId));
        }
    }

    /**
     * @param serverAdmin   the user is a server admin
     * @param adminOverride server admins get Owner access to every network on this server
     */
    public record MeView(UserView user, DeviceView device, boolean serverAdmin, boolean adminOverride) {
    }
}
