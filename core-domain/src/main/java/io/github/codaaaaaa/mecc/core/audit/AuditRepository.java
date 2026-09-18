package io.github.codaaaaaa.mecc.core.audit;

import java.util.List;

public interface AuditRepository {

    void append(AuditEvent event);

    /** Most recent events first. */
    List<AuditEvent> recent(int limit);
}
