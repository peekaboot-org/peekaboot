package org.peekaboot.backend.domain.trace;

/** One query span as the Queries tab lists it, with the statement its span carries. */
public record QueryInfo(
        String spanId,
        String dbSystem,
        String dataSourceName,
        long durationMs,
        Long rowCount,
        SqlStatement statement) {}
