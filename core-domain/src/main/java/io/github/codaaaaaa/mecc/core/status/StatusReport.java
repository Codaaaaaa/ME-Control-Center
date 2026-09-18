package io.github.codaaaaaa.mecc.core.status;

import java.time.Instant;

/**
 * Response model of {@code GET /api/v1/status}.
 *
 * @param timestamp  when the report was assembled
 * @param mecc      ME Control Center build and uptime
 * @param platform   platform adapter description
 * @param state      server availability as seen through the server-thread gateway
 * @param server     server snapshot, or {@code null} unless {@code state == RUNNING}
 * @param ae2        AE2 integration state
 * @param gateway    server-thread gateway health
 */
public record StatusReport(
        Instant timestamp,
        MeccInfo mecc,
        PlatformInfo platform,
        ServerState state,
        ServerSnapshot server,
        Ae2Status ae2,
        GatewayStatus gateway) {

    /**
     * @param version       ME Control Center mod version
     * @param startedAt     when the ME Control Center runtime started
     * @param uptimeSeconds seconds since {@code startedAt}
     */
    public record MeccInfo(String version, Instant startedAt, long uptimeSeconds) {
    }

    /**
     * @param roundTripMillis time from queuing the probe to receiving its result off the server thread;
     *                        {@code null} when the probe failed
     * @param pendingTasks    operations currently waiting for the server thread
     * @param errorCode       failure code when the probe failed, otherwise {@code null}
     */
    public record GatewayStatus(Double roundTripMillis, int pendingTasks, String errorCode) {
    }
}
