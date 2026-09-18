package io.github.codaaaaaa.mecc.core.patterns;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A pattern kept in ME Control Center without being encoded (spec section 16). Drafts belong to one user.
 *
 * @param networkId network the draft was made for, or {@code null}; a hint only, drafts can be encoded anywhere
 */
public record PatternDraft(
        UUID id,
        UUID ownerUuid,
        UUID networkId,
        String name,
        String description,
        PatternDefinition definition,
        Instant createdAt,
        Instant updatedAt) {

    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_DESCRIPTION_LENGTH = 1000;

    public PatternDraft {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        description = description == null ? "" : description;
    }
}
