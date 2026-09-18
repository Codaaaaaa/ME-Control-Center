package io.github.codaaaaaa.mecc.core.patterns;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PatternDraftRepository {

    void insert(PatternDraft draft);

    /** Stores name, description, network, definition, and update time. Returns {@code false} if the draft is gone. */
    boolean update(PatternDraft draft);

    Optional<PatternDraft> find(UUID id);

    /** A user's drafts, most recently updated first. */
    List<PatternDraft> listByOwner(UUID ownerUuid);

    int countByOwner(UUID ownerUuid);

    boolean delete(UUID id);
}
