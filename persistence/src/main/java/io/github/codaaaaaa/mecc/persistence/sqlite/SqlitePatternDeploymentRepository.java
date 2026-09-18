package io.github.codaaaaaa.mecc.persistence.sqlite;

import io.github.codaaaaaa.mecc.core.crafting.OrderTarget;
import io.github.codaaaaaa.mecc.core.patterns.PatternDeployment;
import io.github.codaaaaaa.mecc.core.patterns.PatternDeploymentRepository;
import io.github.codaaaaaa.mecc.core.patterns.PatternType;
import io.github.codaaaaaa.mecc.core.resources.ResourceDescriptor.ResourceUnit;
import io.github.codaaaaaa.mecc.core.resources.ResourceId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

final class SqlitePatternDeploymentRepository implements PatternDeploymentRepository {
    private static final String COLUMNS = "id, network_id, actor_uuid, device_id, draft_id, type, action, output_id, "
            + "output_names, output_mod_id, output_icon_key, unit_symbol, unit_amount, provider_id, provider_name, slot, "
            + "error_code, at";

    private final Jdbc jdbc;

    SqlitePatternDeploymentRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(PatternDeployment deployment) {
        OrderTarget output = deployment.output();
        ResourceUnit unit = output == null ? null : output.unit();
        jdbc.update("INSERT INTO pattern_deployments (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                deployment.id(), deployment.networkId(), deployment.actorUuid(), deployment.deviceId(), deployment.draftId(),
                deployment.type(), deployment.action(),
                output == null ? null : output.resourceId().toString(),
                output == null ? "{}" : StringMapJson.write(output.names()),
                output == null ? null : output.modId(),
                output == null ? null : output.iconKey(),
                unit == null ? null : unit.symbol(), unit == null ? null : unit.amountPerUnit(),
                deployment.providerId(), deployment.providerName(), deployment.slot(), deployment.errorCode(), deployment.at());
    }

    @Override
    public List<PatternDeployment> recent(UUID networkId, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM pattern_deployments WHERE network_id = ? ORDER BY at DESC, id LIMIT ?",
                SqlitePatternDeploymentRepository::map, networkId, limit);
    }

    private static PatternDeployment map(ResultSet row) throws SQLException {
        OrderTarget output = null;
        ResourceId outputId = ResourceId.parse(row.getString("output_id")).orElse(null);
        String modId = row.getString("output_mod_id");
        String iconKey = row.getString("output_icon_key");
        if (outputId != null && modId != null && iconKey != null) {
            String unitSymbol = row.getString("unit_symbol");
            int unitAmount = row.getInt("unit_amount");
            ResourceUnit unit = unitSymbol == null || row.wasNull() ? null : new ResourceUnit(unitSymbol, unitAmount);
            output = new OrderTarget(outputId, StringMapJson.read(row.getString("output_names")), modId, iconKey, unit);
        }
        int slot = row.getInt("slot");
        Integer slotValue = row.wasNull() ? null : slot;
        return new PatternDeployment(
                Jdbc.uuid(row, "id"),
                Jdbc.uuid(row, "network_id"),
                Jdbc.uuid(row, "actor_uuid"),
                row.getString("device_id"),
                Jdbc.uuid(row, "draft_id"),
                PatternType.valueOf(row.getString("type")),
                PatternDeployment.Action.valueOf(row.getString("action")),
                output,
                row.getString("provider_id"),
                row.getString("provider_name"),
                slotValue,
                row.getString("error_code"),
                Jdbc.instant(row, "at"));
    }
}
