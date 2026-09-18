package io.github.codaaaaaa.mecc.core.live;

import io.github.codaaaaaa.mecc.core.auth.Session;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Live updates for connected browsers (spec section 31). The transport authenticates the connection; this
 * service decides what each connection may see and checks network access on every subscription.
 */
public interface LiveEventService {

    /** Registers an authenticated connection. Events for it are delivered to {@code sink} on ME Control Center threads. */
    Connection connect(Session session, Sink sink);

    /** Receives events for one connection. Implementations must not block. */
    interface Sink {
        void send(LiveEvent event);
    }

    interface Connection {
        /**
         * Starts pushing events about a network. Fails with {@code NETWORK_NOT_FOUND} when the user may not
         * see it.
         *
         * @param locale UI locale for names inside payloads
         */
        CompletionStage<Void> subscribe(UUID networkId, String locale);

        void unsubscribe(UUID networkId);

        /** The transport re-authenticated the device, e.g. to pick up a changed admin status. */
        void refresh(Session session);

        /** Idempotent. */
        void close();
    }
}
