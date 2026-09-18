package io.github.codaaaaaa.mecc.core.admin;

import io.github.codaaaaaa.mecc.core.audit.AuditAction;
import io.github.codaaaaaa.mecc.core.audit.AuditEvent.AuditResult;
import io.github.codaaaaaa.mecc.core.auth.AuthViews.UserView;
import io.github.codaaaaaa.mecc.core.config.MeccConfig;
import io.github.codaaaaaa.mecc.core.status.PlatformInfo;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** API response models for administration and the audit log. */
public final class AdminViews {
    private AdminViews() {
    }

    /**
     * @param actor        acting player, or {@code null} for the console/system
     * @param networkName  current name of the network, or {@code null} when none or deleted
     * @param targetPlayer the target as a known player, when the target is one
     */
    public record AuditEntryView(long id, Instant at, UserView actor, String deviceId, String networkId,
                                 String networkName, AuditAction action, String target, UserView targetPlayer,
                                 AuditResult result, boolean adminOverride, Map<String, String> parameters) {
    }

    /** @param nextBefore pass as {@code before} for older entries, or {@code null} at the end */
    public record AuditPage(List<AuditEntryView> entries, Long nextBefore) {
    }

    public record BackupView(String name, Instant createdAt, long sizeBytes) {
    }

    /** @param backups newest first */
    public record DatabaseView(String file, int schemaVersion, long sizeBytes, List<BackupView> backups) {
    }

    /**
     * A content pack in {@code config/mecc/content-packs/} (spec sections 19 and 45). A mismatched pack is still
     * used for what did not change; the problems say what did.
     *
     * @param fingerprint the exporting installation's fingerprint, or {@code null} when unreadable
     * @param minecraft   Minecraft version it was made for, or {@code null}
     * @param icons       icons it holds, or {@code null} when unknown
     */
    public record ContentPackView(String name, String fingerprint, String createdAt, String minecraft, Integer icons,
                                  List<String> locales, PackStatus status, List<String> problems) {
        public enum PackStatus {
            MATCH,
            MISMATCH,
            /** Not used: not a content pack, unreadable, or from a newer exporter. */
            SKIPPED
        }
    }

    /** @param configFile where {@code config} was read from; changes take effect after a restart */
    public record AdminOverview(String meccVersion, PlatformInfo platform, String configFile, MeccConfig config,
                                DatabaseView database, List<ContentPackView> contentPacks) {
    }
}
