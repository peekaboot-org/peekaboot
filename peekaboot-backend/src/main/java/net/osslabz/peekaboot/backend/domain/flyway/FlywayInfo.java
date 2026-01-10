package net.osslabz.peekaboot.backend.domain.flyway;

import java.util.List;

/**
 * Domain record for Flyway database migration information.
 */
public record FlywayInfo(
    List<MigrationInfo> migrations
) {}
