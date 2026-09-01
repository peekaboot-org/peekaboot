package org.peekaboot.backend.domain.trace;

import java.util.List;

public record TraceTree(
        String traceId,
        long startTimeMs,
        long durationMs,
        TraceStatus status,
        RootActionType rootActionType,
        String rootOperation,
        SpanNode rootSpan,
        TraceTabSummary summary,
        HttpExchange httpExchange,
        List<TraceLog> logs,
        List<QueryInfo> queries,
        boolean truncated) {}
