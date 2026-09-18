package io.github.codaaaaaa.mecc.persistence.sqlite;

import io.github.codaaaaaa.mecc.core.crafting.OrderTarget;
import io.github.codaaaaaa.mecc.core.resources.ResourceDescriptor.ResourceUnit;
import io.github.codaaaaaa.mecc.core.resources.ResourceId;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * A frozen resource ({@link OrderTarget}) in the six {@link #COLUMNS} shared by saved orders and alerts. A {@code null}
 * target is stored as six {@code NULL}s.
 */
final class StoredTargets {
    static final String COLUMNS = "resource_id, resource_names, resource_mod_id, resource_icon_key, unit_symbol, unit_amount";

    private StoredTargets() {
    }

    /** Parameters for {@link #COLUMNS}, in order. */
    static Object[] values(OrderTarget target) {
        if (target == null) {
            return new Object[6];
        }
        ResourceUnit unit = target.unit();
        return new Object[] {target.resourceId().toString(), StringMapJson.write(target.names()), target.modId(),
                target.iconKey(), unit == null ? null : unit.symbol(), unit == null ? null : unit.amountPerUnit()};
    }

    static OrderTarget read(ResultSet row) throws SQLException {
        String id = row.getString("resource_id");
        if (id == null) {
            return null;
        }
        String unitSymbol = row.getString("unit_symbol");
        int unitAmount = row.getInt("unit_amount");
        ResourceUnit unit = unitSymbol == null || row.wasNull() ? null : new ResourceUnit(unitSymbol, unitAmount);
        return new OrderTarget(ResourceId.parse(id).orElseThrow(), StringMapJson.read(row.getString("resource_names")),
                row.getString("resource_mod_id"), row.getString("resource_icon_key"), unit);
    }

    /** {@code head}, then the target's values, then {@code tail}. */
    static Object[] params(Object[] head, OrderTarget target, Object... tail) {
        Object[] values = values(target);
        Object[] all = new Object[head.length + values.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(values, 0, all, head.length, values.length);
        System.arraycopy(tail, 0, all, head.length + values.length, tail.length);
        return all;
    }
}
