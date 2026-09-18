package io.github.codaaaaaa.mecc.core.status;

/**
 * AE2 integration state.
 *
 * @param loaded        whether AE2 is present
 * @param version       installed AE2 version, or {@code null} when not loaded
 * @param testedVersion the AE2 version this ME Control Center build is tested against
 * @param tested        whether {@code version} equals {@code testedVersion}
 */
public record Ae2Status(boolean loaded, String version, String testedVersion, boolean tested) {
    public static Ae2Status of(String installedVersion, String testedVersion) {
        boolean loaded = installedVersion != null;
        return new Ae2Status(loaded, installedVersion, testedVersion, loaded && installedVersion.equals(testedVersion));
    }
}
