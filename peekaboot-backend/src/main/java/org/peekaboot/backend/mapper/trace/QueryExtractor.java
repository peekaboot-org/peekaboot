package org.peekaboot.backend.mapper.trace;

import java.util.List;
import org.peekaboot.backend.domain.trace.QueryInfo;
import org.peekaboot.backend.tracing.event.SpanData;
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

        return new QueryInfo(
                span.spanId(), dbSystem, dataSourceName, span.durationMs(), queries.rowCount(span.spanId()));
    }
}
