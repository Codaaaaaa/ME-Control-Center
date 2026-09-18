package io.github.codaaaaaa.mecc.runtime.status;

import io.github.codaaaaaa.mecc.core.error.ErrorCode;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import io.github.codaaaaaa.mecc.core.status.Ae2Status;
import io.github.codaaaaaa.mecc.core.status.ServerState;
import io.github.codaaaaaa.mecc.core.status.StatusReport;
import io.github.codaaaaaa.mecc.core.status.StatusReport.GatewayStatus;
import io.github.codaaaaaa.mecc.core.status.StatusReport.MeccInfo;
import io.github.codaaaaaa.mecc.core.status.StatusService;
import io.github.codaaaaaa.mecc.platform.MeccPlatform;
import io.github.codaaaaaa.mecc.platform.thread.ServerThreadGateway;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * Builds the status report. The only server-thread work is copying a {@code ServerSnapshot}; the
 * report is assembled on the worker thread that completes the gateway future.
 */
public final class DefaultStatusService implements StatusService {
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

    private final MeccPlatform platform;
    private final ServerThreadGateway gateway;
    private final Clock clock;
    private final Instant startedAt;

    public DefaultStatusService(MeccPlatform platform, ServerThreadGateway gateway, Clock clock, Instant startedAt) {
        this.platform = platform;
        this.gateway = gateway;
        this.clock = clock;
        this.startedAt = startedAt;
    }

    @Override
    public CompletionStage<StatusReport> currentStatus() {
        long probeStart = System.nanoTime();
        return gateway.call("status.snapshot", platform.serverInfo()::snapshot, PROBE_TIMEOUT)
                .handle((snapshot, error) -> {
                    Double roundTripMillis = null;
                    String errorCode = null;
                    ServerState state;
                    if (error == null) {
                        state = ServerState.RUNNING;
                        roundTripMillis = (System.nanoTime() - probeStart) / 1_000_000.0;
                    } else {
                        ErrorCode code = gatewayErrorCode(error);
                        errorCode = code.name();
                        state = code == ErrorCode.SERVER_UNAVAILABLE ? ServerState.UNAVAILABLE : ServerState.BUSY;
                    }
                    Instant now = clock.instant();
                    return new StatusReport(
                            now,
                            new MeccInfo(platform.meccVersion(), startedAt, Duration.between(startedAt, now).toSeconds()),
                            platform.info(),
                            state,
                            snapshot,
                            Ae2Status.of(platform.ae2().installedVersion().orElse(null), platform.ae2().testedVersion()),
                            new GatewayStatus(roundTripMillis, gateway.pendingTasks(), errorCode));
                });
    }

    /** Gateway availability errors degrade the report; anything else is a real failure and propagates. */
    private static ErrorCode gatewayErrorCode(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof MeccException mecc) {
            switch (mecc.code()) {
                case SERVER_UNAVAILABLE, GATEWAY_BUSY, SERVER_THREAD_TIMEOUT:
                    return mecc.code();
                default:
                    break;
            }
        }
        throw cause instanceof CompletionException completion ? completion : new CompletionException(cause);
    }
}
