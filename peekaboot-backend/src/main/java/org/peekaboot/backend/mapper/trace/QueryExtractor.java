package org.peekaboot.backend.mapper.trace;

import java.time.Instant;
import java.util.List;
import org.peekaboot.backend.domain.trace.QueryInfo;
import org.peekaboot.backend.tracing.store.SpanData;
import org.peekaboot.backend.tracing.store.TraceData;

public class QueryExtractor {

    /** {@code queries} comes from {@link TraceTreeMapper#queriesOf} for the same {@code traceData}. */
    public List<QueryInfo> extract(TraceData traceData, TraceQueries queries) {
        return traceData.spans().stream()
                .filter(DbSpans::isQuery)
                .map(span -> extractQuery(span, queries))
                .toList();
    }

    private QueryInfo extractQuery(SpanData span, TraceQueries queries) {
        String dbSystem = DbSpans.system(span.tags());
        String dataSourceName = DbSpans.dataSourceName(span.tags());

        Instant timestamp = span.startTime();
        long creationOrder = span.creationOrder();

        return new QueryInfo(
                span.spanId(),
                queries.statement(span),
                dbSystem,
                dataSourceName,
                span.durationMs(),
                timestamp,
                queries.rowCount(span.spanId()),
                creationOrder);
    }
}
