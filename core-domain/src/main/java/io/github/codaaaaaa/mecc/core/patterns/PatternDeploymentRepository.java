package io.github.codaaaaaa.mecc.core.patterns;

import java.util.List;
import java.util.UUID;

public interface PatternDeploymentRepository {

    void insert(PatternDeployment deployment);

    /** A network's most recent attempts, newest first. */
    List<PatternDeployment> recent(UUID networkId, int limit);
}
