package io.github.codaaaaaa.mecc.core.networks;

/** Persisted identity-resolution status of an ME Control Center network record. */
public enum NetworkRecordStatus {
    /** Resolved to exactly one loaded grid that contains no other network's anchors. */
    ONLINE,
    /** None of its anchors is currently loaded. */
    OFFLINE,
    /**
     * Identity is ambiguous: its anchors are spread over several grids (split) or its grid also holds
     * another network's anchors (merge). No access is granted to live data until it resolves.
     */
    CONFLICT
}
