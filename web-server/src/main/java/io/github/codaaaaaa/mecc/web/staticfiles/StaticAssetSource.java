package io.github.codaaaaaa.mecc.web.staticfiles;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Reads bundled files by relative path (forward slashes). Abstracts over where the web UI lives:
 * inside a mod jar in production, in memory in tests.
 */
@FunctionalInterface
public interface StaticAssetSource {
    Optional<InputStream> open(String path) throws IOException;
}
