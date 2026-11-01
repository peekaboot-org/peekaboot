package org.peekaboot.backend.mapper.trace;

import java.util.List;
import java.util.Set;
import org.peekaboot.backend.domain.trace.TraceLog;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.store.TraceBucket;

/**
 * What the store knows about a trace beside its spans, as one summary reads it.
 *
 * @param storeFailed the store's sticky failure flag; false on a subtree row, whose enclosing request it covers
 * @param logs one read of the logs the summary counts
 * @param errorLogs the ERROR logs among {@code logs}, mapped for the wire
 */
record TraceFacts(
        boolean storeFailed,
        Set<TraceBucket> buckets,
        List<LogCapturedEvent> logs,
        List<TraceLog> errorLogs,
        int droppedSpans,
        int droppedLogs) {}
