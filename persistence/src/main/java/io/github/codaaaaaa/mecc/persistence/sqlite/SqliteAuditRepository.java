package io.github.codaaaaaa.mecc.persistence.sqlite;

import io.github.codaaaaaa.mecc.core.audit.AuditAction;
import io.github.codaaaaaa.mecc.core.audit.AuditEvent;
import io.github.codaaaaaa.mecc.core.audit.AuditEvent.AuditResult;
import io.github.codaaaaaa.mecc.core.audit.AuditRepository;
import java.util.List;

final class SqliteAuditRepository implements AuditRepository {
    private final Jdbc jdbc;

    SqliteAuditRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(AuditEvent event) {
        jdbc.update("INSERT INTO audit_log (at, actor_player_uuid, device_id, network_id, action, target, result, "
                        + "admin_override, parameters) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                event.at(), event.actorPlayerUuid(), event.deviceId(), event.networkId(), event.action(),
                event.target(), event.result(), event.adminOverride(), StringMapJson.write(event.parameters()));
    }

    @Override
    public List<AuditEvent> recent(int limit) {
        return jdbc.query("SELECT at, actor_player_uuid, device_id, network_id, action, target, result, admin_override, "
                        + "parameters FROM audit_log ORDER BY id DESC LIMIT ?",
                row -> new AuditEvent(Jdbc.instant(row, "at"), Jdbc.uuid(row, "actor_player_uuid"),
                        row.getString("device_id"), Jdbc.uuid(row, "network_id"),
                        AuditAction.valueOf(row.getString("action")), row.getString("target"),
                        AuditResult.valueOf(row.getString("result")), row.getInt("admin_override") != 0,
                        StringMapJson.read(row.getString("parameters"))),
                limit);
    }
}
