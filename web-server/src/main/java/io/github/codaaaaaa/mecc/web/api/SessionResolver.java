package io.github.codaaaaaa.mecc.web.api;

import io.github.codaaaaaa.mecc.core.auth.ClientInfo;
import io.github.codaaaaaa.mecc.core.auth.Session;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Resolves a presented device token into a session. */
@FunctionalInterface
public interface SessionResolver {
    CompletionStage<Optional<Session>> resolve(String token, ClientInfo client);

    static SessionResolver none() {
        return (token, client) -> CompletableFuture.completedFuture(Optional.empty());
    }
}
