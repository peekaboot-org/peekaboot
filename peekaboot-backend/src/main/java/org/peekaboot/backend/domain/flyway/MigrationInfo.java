package org.peekaboot.backend.domain.flyway;

import java.time.Instant;

/** One Flyway migration. */
public record MigrationInfo(
        String version,
        String description,
        String type,
        MigrationState state,
        Instant installedOn,
        Integer executionTime,
        String script) {}
