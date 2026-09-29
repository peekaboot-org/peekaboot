package org.peekaboot.backend.domain.trace;

import java.time.Instant;

public record QueryInfo(
        String spanId,
        SqlStatement statement,
        String dbSystem,
        long durationMs,
        Instant timestamp,
        Long rowCount,
        long creationOrder) {}
