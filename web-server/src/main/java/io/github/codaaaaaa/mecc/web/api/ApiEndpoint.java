package io.github.codaaaaaa.mecc.web.api;

import java.util.concurrent.CompletionStage;

/**
 * An API endpoint. Must return quickly: long work belongs in the returned stage.
 * The result is serialized to JSON on whichever thread completes the stage (never the server thread
 * when the stage comes from the ServerThreadGateway).
 */
@FunctionalInterface
public interface ApiEndpoint {
    CompletionStage<?> handle(ApiRequest request);
}
