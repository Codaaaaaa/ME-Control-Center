package io.github.codaaaaaa.mecc.core.auth;

import io.github.codaaaaaa.mecc.core.users.WebUser;
import java.util.Objects;

/**
 * An authenticated request context.
 *
 * @param user         the signed-in player
 * @param device       the device whose token was presented
 * @param serverAdmin  whether the player currently counts as server admin (operator level check)
 * @param renewCookie  whether the transport should re-send the device cookie to extend its lifetime
 */
public record Session(WebUser user, Device device, boolean serverAdmin, boolean renewCookie) {
    public Session {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(device, "device");
    }
}
