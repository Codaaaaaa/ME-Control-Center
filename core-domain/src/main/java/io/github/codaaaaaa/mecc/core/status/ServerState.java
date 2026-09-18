package io.github.codaaaaaa.mecc.core.status;

public enum ServerState {
    /** The server thread answered the status probe. */
    RUNNING,
    /** The server thread is busy: the probe was rejected or did not start in time. */
    BUSY,
    /** The server is stopping or not running. */
    UNAVAILABLE
}
