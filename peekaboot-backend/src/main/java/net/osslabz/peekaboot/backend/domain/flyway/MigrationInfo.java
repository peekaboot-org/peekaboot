package net.osslabz.peekaboot.backend.domain.flyway;

import java.time.Instant;

/**
 * Rich domain record for Flyway migration information.
 *
 * Uses explicit typing because migrations need sorting
 * and status mapping logic.
 */
public record MigrationInfo(
    String version,
    String description,
    String type,
    MigrationState state,
    Instant installedOn,
    Integer executionTime,
    String script
) {}
