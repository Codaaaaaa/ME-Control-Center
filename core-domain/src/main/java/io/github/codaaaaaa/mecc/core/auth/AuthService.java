package io.github.codaaaaaa.mecc.core.auth;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Browser pairing, device-token authentication, and device management.
 * Implementations never block the calling thread.
 */
public interface AuthService {

    /**
     * Redeems a pairing key and creates a device. Rate-limited per client address.
     *
     * @param requestedName optional device label; derived from the User-Agent when blank
     * @return the new session and the plaintext device token (returned exactly once)
     */
    CompletionStage<PairingOutcome> pair(String pairingKey, String requestedName, ClientInfo client);

    /** Resolves a device token. Empty for unknown or revoked tokens. */
    CompletionStage<Optional<Session>> authenticate(String token, ClientInfo client);

    /** Revokes the session's own device. */
    CompletionStage<Void> logout(Session session);

    CompletionStage<AuthViews.MeView> me(Session session);

    CompletionStage<List<AuthViews.DeviceView>> listDevices(Session session);

    CompletionStage<AuthViews.DeviceView> renameDevice(Session session, String deviceId, String name);

    /** Revokes one of the caller's own devices (the current one included). */
    CompletionStage<Void> revokeDevice(Session session, String deviceId);

    /** Revokes every device of the user except the current one. Returns how many were revoked. */
    CompletionStage<Integer> revokeOtherDevices(Session session);

    record PairingOutcome(Session session, String token) {
    }
}
