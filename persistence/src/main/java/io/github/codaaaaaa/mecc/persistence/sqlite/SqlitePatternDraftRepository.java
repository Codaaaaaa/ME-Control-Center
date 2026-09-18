package io.github.codaaaaaa.mecc.persistence.sqlite;

import io.github.codaaaaaa.mecc.core.patterns.PatternDefinition;
import io.github.codaaaaaa.mecc.core.patterns.PatternDraft;
import io.github.codaaaaaa.mecc.core.patterns.PatternDraftRepository;
import io.github.codaaaaaa.mecc.core.patterns.PatternStack;
import io.github.codaaaaaa.mecc.core.patterns.PatternType;
import io.github.codaaaaaa.mecc.core.resources.ResourceId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Drafts are stored relationally: one row per draft, one row per non-empty input or output slot. Empty
 * slots are the gaps between stored slot numbers, so no JSON is needed.
 */
final class SqlitePatternDraftRepository implements PatternDraftRepository {
    private static final String COLUMNS = "id, owner_uuid, network_id, name, description, type, substitutes, "
            + "fluid_substitutes, recipe_id, created_at, updated_at";
    private static final String INPUT = "in";
    private static final String OUTPUT = "out";

    private final Jdbc jdbc;

    SqlitePatternDraftRepository(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(PatternDraft draft) {
        PatternDefinition definition = draft.definition();
        jdbc.update("INSERT INTO pattern_drafts (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                draft.id(), draft.ownerUuid(), draft.networkId(), draft.name(), draft.description(), definition.type(),
                definition.substitutes(), definition.fluidSubstitutes(), definition.recipeId(), draft.createdAt(),
                draft.updatedAt());
        insertStacks(draft.id(), definition);
    }

    @Override
    public boolean update(PatternDraft draft) {
        PatternDefinition definition = draft.definition();
        boolean exists = jdbc.update("UPDATE pattern_drafts SET network_id = ?, name = ?, description = ?, type = ?, "
                        + "substitutes = ?, fluid_substitutes = ?, recipe_id = ?, updated_at = ? WHERE id = ?",
                draft.networkId(), draft.name(), draft.description(), definition.type(), definition.substitutes(),
                definition.fluidSubstitutes(), definition.recipeId(), draft.updatedAt(), draft.id()) > 0;
        if (exists) {
            jdbc.update("DELETE FROM pattern_draft_stacks WHERE draft_id = ?", draft.id());
            insertStacks(draft.id(), definition);
        }
        return exists;
    }

    private void insertStacks(UUID draftId, PatternDefinition definition) {
        insertStacks(draftId, INPUT, definition.inputs());
        insertStacks(draftId, OUTPUT, definition.outputs());
        // The slot count of fixed-size types is implied by the type; for processing, trailing empty slots
        // carry no meaning. Nothing else needs to be stored.
    }

    private void insertStacks(UUID draftId, String role, List<PatternStack> stacks) {
        for (int slot = 0; slot < stacks.size(); slot++) {
            PatternStack stack = stacks.get(slot);
            if (stack != null) {
                jdbc.update("INSERT INTO pattern_draft_stacks (draft_id, role, slot, resource_id, amount) VALUES (?, ?, ?, ?, ?)",
                        draftId, role, slot, stack.resource().toString(), stack.amount());
            }
        }
    }

    @Override
    public Optional<PatternDraft> find(UUID id) {
        List<Row> rows = jdbc.query("SELECT " + COLUMNS + " FROM pattern_drafts WHERE id = ?", SqlitePatternDraftRepository::row, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(withStacks(rows).get(0));
    }

    @Override
    public List<PatternDraft> listByOwner(UUID ownerUuid) {
        return withStacks(jdbc.query("SELECT " + COLUMNS + " FROM pattern_drafts WHERE owner_uuid = ? ORDER BY updated_at DESC, id",
                SqlitePatternDraftRepository::row, ownerUuid));
    }

    @Override
    public int countByOwner(UUID ownerUuid) {
        return jdbc.queryOne("SELECT COUNT(*) AS n FROM pattern_drafts WHERE owner_uuid = ?", result -> result.getInt("n"),
                ownerUuid).orElse(0);
    }

    @Override
    public boolean delete(UUID id) {
        return jdbc.update("DELETE FROM pattern_drafts WHERE id = ?", id) > 0;
    }

    private record Row(UUID id, UUID owner, UUID networkId, String name, String description, PatternType type,
                       boolean substitutes, boolean fluidSubstitutes, String recipeId, Instant createdAt, Instant updatedAt) {
    }

    private record StackRow(UUID draftId, String role, int slot, PatternStack stack) {
    }

    private static Row row(ResultSet result) throws SQLException {
        return new Row(Jdbc.uuid(result, "id"), Jdbc.uuid(result, "owner_uuid"), Jdbc.uuid(result, "network_id"),
                result.getString("name"), result.getString("description"), PatternType.valueOf(result.getString("type")),
                result.getInt("substitutes") != 0, result.getInt("fluid_substitutes") != 0, result.getString("recipe_id"),
                Jdbc.instant(result, "created_at"), Jdbc.instant(result, "updated_at"));
    }

    /** Loads the slots of all rows with one query per row set. */
    private List<PatternDraft> withStacks(List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT draft_id, role, slot, resource_id, amount FROM pattern_draft_stacks WHERE draft_id IN (");
        List<Object> params = new ArrayList<>();
        for (Row row : rows) {
            sql.append(params.isEmpty() ? "?" : ", ?");
            params.add(row.id());
        }
        sql.append(") ORDER BY draft_id, role, slot");
        Map<UUID, List<StackRow>> stacks = new HashMap<>();
        for (StackRow stack : jdbc.query(sql.toString(), SqlitePatternDraftRepository::stackRow, params.toArray())) {
            if (stack != null) {
                stacks.computeIfAbsent(stack.draftId(), id -> new ArrayList<>()).add(stack);
            }
        }

        List<PatternDraft> drafts = new ArrayList<>(rows.size());
        for (Row row : rows) {
            List<StackRow> own = stacks.getOrDefault(row.id(), List.of());
            int fixed = row.type().fixedInputSlots();
            List<PatternStack> inputs = slots(own, INPUT, fixed);
            List<PatternStack> outputs = slots(own, OUTPUT, -1);
            drafts.add(new PatternDraft(row.id(), row.owner(), row.networkId(), row.name(), row.description(),
                    new PatternDefinition(row.type(), inputs, outputs, row.substitutes(), row.fluidSubstitutes(), row.recipeId()),
                    row.createdAt(), row.updatedAt()));
        }
        return drafts;
    }

    private static List<PatternStack> slots(List<StackRow> stacks, String role, int fixedSize) {
        int size = Math.max(fixedSize, 0);
        for (StackRow stack : stacks) {
            if (stack.role().equals(role)) {
                size = Math.max(size, stack.slot() + 1);
            }
        }
        List<PatternStack> slots = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            slots.add(null);
        }
        for (StackRow stack : stacks) {
            if (stack.role().equals(role)) {
                slots.set(stack.slot(), stack.stack());
            }
        }
        return slots;
    }

    /** A slot whose resource ID no longer parses (hand-edited database) is dropped rather than failing the draft. */
    private static StackRow stackRow(ResultSet result) throws SQLException {
        Optional<ResourceId> id = ResourceId.parse(result.getString("resource_id"));
        long amount = result.getLong("amount");
        int slot = result.getInt("slot");
        if (id.isEmpty() || amount < 1 || slot < 0 || slot > 255) {
            return null;
        }
        return new StackRow(Jdbc.uuid(result, "draft_id"), result.getString("role"), slot, new PatternStack(id.get(), amount));
    }
}
