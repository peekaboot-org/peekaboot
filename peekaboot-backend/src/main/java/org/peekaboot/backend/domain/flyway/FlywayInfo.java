package org.peekaboot.backend.domain.flyway;

import java.util.List;

public record FlywayInfo(List<MigrationInfo> migrations) {}
