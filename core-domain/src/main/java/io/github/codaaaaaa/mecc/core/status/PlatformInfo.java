package io.github.codaaaaaa.mecc.core.status;

/**
 * Static description of the platform adapter ME Control Center is running on.
 *
 * @param platformId       adapter identifier, e.g. {@code forge-1.20.1}
 * @param minecraftVersion e.g. {@code 1.20.1}
 * @param loader           e.g. {@code forge}
 * @param loaderVersion    e.g. {@code 47.4.16}
 */
public record PlatformInfo(String platformId, String minecraftVersion, String loader, String loaderVersion) {
}
