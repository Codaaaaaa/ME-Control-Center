package io.github.codaaaaaa.mecc.persistence.sqlite;

import io.github.codaaaaaa.mecc.core.crafting.CraftingOrder;
import io.github.codaaaaaa.mecc.core.crafting.CraftingOrderRepository;
import io.github.codaaaaaa.mecc.core.crafting.OrderEvent;
import io.github.codaaaaaa.mecc.core.crafting.OrderEventType;
import io.github.codaaaaaa.mecc.core.crafting.OrderSource;
import io.github.codaaaaaa.mecc.core.crafting.OrderState;
import io.github.codaaaaaa.mecc.core.crafting.OrderTarget;
import io.github.codaaaaaa.mecc.core.resources.ResourceDescriptor.ResourceUnit;
import io.github.codaaaaaa.mecc.core.resources.ResourceId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class SqliteOrderRepository implements CraftingOrderRepository {
    private static final String COLUMNS = "id, network_id, creator_uuid, device_id, source, resource_id, resource_names, "
            + "resource_mod_id, resource_icon_key, unit_symbol, unit_amount, amount, state, job_id, cpu_id, cpu_name, "
            + "plan_bytes, created_at, started_at, ended_at, last_observed_at, progress_percent, failure_code, failure_message";

    private final Jdbc jdbc;

    SqliteOrderRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(CraftingOrder order) {
        OrderTarget target = order.target();
        ResourceUnit unit = target.unit();
        jdbc.update("INSERT INTO orders (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                order.id(), order.networkId(), order.creatorUuid(), order.deviceId(), order.source(),
                target.resourceId().toString(), StringMapJson.write(target.names()), target.modId(), target.iconKey(),
                unit == null ? null : unit.symbol(), unit == null ? null : unit.amountPerUnit(),
                order.amount(), order.state(), order.jobId(), order.cpuId(), order.cpuName(), order.planBytes(),
                order.createdAt(), order.startedAt(), order.endedAt(), order.lastObservedAt(), order.progressPercent(),
                order.failureCode(), order.failureMessage());
    }

    @Override
    public Optional<CraftingOrder> find(UUID id) {
        return jdbc.queryOne("SELECT " + COLUMNS + " FROM orders WHERE id = ?", SqliteOrderRepository::map, id);
    }

    @Override
    public boolean update(CraftingOrder order) {
        return jdbc.update("UPDATE orders SET state = ?, job_id = ?, cpu_id = ?, cpu_name = ?, plan_bytes = ?, "
                        + "started_at = ?, ended_at = ?, last_observed_at = ?, progress_percent = ?, failure_code = ?, "
                        + "failure_message = ? WHERE id = ?",
                order.state(), order.jobId(), order.cpuId(), order.cpuName(), order.planBytes(), order.startedAt(),
                order.endedAt(), order.lastObservedAt(), order.progressPercent(), order.failureCode(),
                order.failureMessage(), order.id()) > 0;
    }

    @Override
    public List<CraftingOrder> list(UUID networkId, Set<OrderState> states, int limit, Cursor before) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM orders WHERE network_id = ?");
        params.add(networkId);
        appendStates(sql, params, states);
        if (before != null) {
            sql.append(" AND (created_at < ? OR (created_at = ? AND id < ?))");
            params.add(before.createdAt());
            params.add(before.createdAt());
            params.add(before.id());
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        params.add(limit);
        return jdbc.query(sql.toString(), SqliteOrderRepository::map, params.toArray());
    }

    @Override
    public List<CraftingOrder> listByStates(Set<OrderState> states) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM orders WHERE 1 = 1");
        appendStates(sql, params, states);
        sql.append(" ORDER BY created_at");
        return jdbc.query(sql.toString(), SqliteOrderRepository::map, params.toArray());
    }

    private static void appendStates(StringBuilder sql, List<Object> params, Set<OrderState> states) {
        sql.append(" AND state IN (");
        if (states.isEmpty()) {
            sql.append("NULL");
        }
        boolean first = true;
        for (OrderState state : states) {
            sql.append(first ? "?" : ", ?");
            first = false;
            params.add(state);
        }
        sql.append(')');
    }

    @Override
    public void appendEvent(OrderEvent event) {
        jdbc.update("INSERT INTO order_events (order_id, at, type, actor_player_uuid, details) VALUES (?, ?, ?, ?, ?)",
                event.orderId(), event.at(), event.type(), event.actorUuid(), StringMapJson.write(event.details()));
    }

    @Override
    public List<OrderEvent> events(UUID orderId) {
        return jdbc.query("SELECT order_id, at, type, actor_player_uuid, details FROM order_events WHERE order_id = ? ORDER BY id",
                row -> new OrderEvent(Jdbc.uuid(row, "order_id"), Jdbc.instant(row, "at"),
                        OrderEventType.valueOf(row.getString("type")), Jdbc.uuid(row, "actor_player_uuid"),
                        StringMapJson.read(row.getString("details"))),
                orderId);
    }

    private static CraftingOrder map(ResultSet row) throws SQLException {
        String unitSymbol = row.getString("unit_symbol");
        int unitAmount = row.getInt("unit_amount");
        ResourceUnit unit = unitSymbol == null || row.wasNull() ? null : new ResourceUnit(unitSymbol, unitAmount);
        String orderId = row.getString("id");
        ResourceId resourceId = ResourceId.parse(row.getString("resource_id"))
                .orElseThrow(() -> new SQLException("Corrupt resource id in order " + orderId));
        OrderTarget target = new OrderTarget(resourceId, StringMapJson.read(row.getString("resource_names")),
                row.getString("resource_mod_id"), row.getString("resource_icon_key"), unit);
        long planBytes = row.getLong("plan_bytes");
        Long planBytesValue = row.wasNull() ? null : planBytes;
        double progress = row.getDouble("progress_percent");
        Double progressValue = row.wasNull() ? null : progress;
        return new CraftingOrder(
                Jdbc.uuid(row, "id"),
                Jdbc.uuid(row, "network_id"),
                Jdbc.uuid(row, "creator_uuid"),
                row.getString("device_id"),
                OrderSource.valueOf(row.getString("source")),
                target,
                row.getLong("amount"),
                OrderState.valueOf(row.getString("state")),
                row.getString("job_id"),
                row.getString("cpu_id"),
                row.getString("cpu_name"),
                planBytesValue,
                Jdbc.instant(row, "created_at"),
                Jdbc.instant(row, "started_at"),
                Jdbc.instant(row, "ended_at"),
                Jdbc.instant(row, "last_observed_at"),
                progressValue,
                row.getString("failure_code"),
                row.getString("failure_message"));
    }
}
