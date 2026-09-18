package io.github.codaaaaaa.mecc.core.audit;

import java.util.List;
import java.util.UUID;

public interface AuditRepository {

    void append(AuditEvent event);

    /**
     * Most recent events first.
     *
     * @param networkId only events of this network, or {@code null} for all
     * @param beforeId  only events older than this ID (paging), or {@code null}
     */
    List<AuditRecord> list(UUID networkId, Long beforeId, int limit);

    /** A stored event with its ID, which orders events and pages through them. */
    record AuditRecord(long id, AuditEvent event) {
    }
}
