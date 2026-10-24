package org.peekaboot.backend.domain.flyway;

import java.time.Instant;

public record MigrationInfo(
        String version,
        String description,
        String type,
        MigrationState state,
        Instant installedOn,
        Integer executionTimeMs,
        String script) {}
