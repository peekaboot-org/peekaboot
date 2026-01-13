package net.osslabz.peekaboot.backend.domain.trace;

import java.time.Instant;

public record QueryInfo(
        String spanId,
        String sql,
        String dbSystem,
        long durationMs,
        Instant timestamp,
        Long rowCount,
        long creationOrder
) {
    /**
     * Convenience constructor without rowCount and creationOrder (for backwards compatibility).
     */
    public QueryInfo(String spanId, String sql, String dbSystem, long durationMs, Instant timestamp) {
        this(spanId, sql, dbSystem, durationMs, timestamp, null, 0);
    }
}
