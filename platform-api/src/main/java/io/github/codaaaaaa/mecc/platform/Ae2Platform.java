package io.github.codaaaaaa.mecc.platform;

import java.util.Optional;

/**
 * AE2 integration boundary. All AE2 API access lives in the adapter's implementation of this
 * interface (and, in later milestones, the network/storage/crafting/pattern capabilities it exposes).
 */
public interface Ae2Platform {

    /** Installed AE2 version, or empty when AE2 is not loaded. Thread-safe. */
    Optional<String> installedVersion();

    /** The AE2 version this adapter build is tested against, e.g. {@code 15.4.10}. Thread-safe. */
    String testedVersion();
}
