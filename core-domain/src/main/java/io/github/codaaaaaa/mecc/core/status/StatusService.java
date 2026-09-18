package io.github.codaaaaaa.mecc.core.status;

import java.util.concurrent.CompletionStage;

/** Application service behind the status endpoint. Implementations must not block the calling thread. */
public interface StatusService {
    CompletionStage<StatusReport> currentStatus();
}
