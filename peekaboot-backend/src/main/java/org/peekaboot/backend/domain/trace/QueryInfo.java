package org.peekaboot.backend.domain.trace;

public record QueryInfo(
        String spanId,
        SqlStatement statement,
        String dbSystem,
        String dataSourceName,
        long durationMs,
        Long rowCount) {}
