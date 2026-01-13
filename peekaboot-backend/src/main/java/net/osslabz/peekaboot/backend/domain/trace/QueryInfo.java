package net.osslabz.peekaboot.backend.domain.trace;

import java.time.Instant;

public record QueryInfo(
        String spanId,
        String sql,
        String dbSystem,
        long durationMs,
        Instant timestamp
) {
}
