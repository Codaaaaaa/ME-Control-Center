package io.github.codaaaaaa.mecc.core.admin;

import io.github.codaaaaaa.mecc.core.admin.AdminViews.AdminOverview;
import io.github.codaaaaaa.mecc.core.admin.AdminViews.AuditPage;
import io.github.codaaaaaa.mecc.core.admin.AdminViews.BackupView;
import io.github.codaaaaaa.mecc.core.auth.Session;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Server administration and the audit log (spec sections 35 and 50, milestone 6). */
public interface AdminService {

    /** Effective configuration and database state. Server admins only ({@code PERMISSION_DENIED}). */
    CompletionStage<AdminOverview> overview(Session session);

    /** Writes a consistent copy of the database into the backups folder. Server admins only; audited. */
    CompletionStage<BackupView> backup(Session session);

    /**
     * Audit log, newest first: of one network (needs {@code VIEW_AUDIT_LOG}) or, when {@code networkId} is
     * {@code null}, of the whole server (server admins only).
     *
     * @param before only entries older than this entry ID, or {@code null}
     */
    CompletionStage<AuditPage> auditLog(Session session, UUID networkId, Long before, int limit);
}
