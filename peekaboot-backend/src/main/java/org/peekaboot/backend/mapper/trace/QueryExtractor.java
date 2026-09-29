package org.peekaboot.backend.mapper.trace;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.peekaboot.backend.domain.trace.QueryInfo;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.tracing.store.SpanData;
import org.peekaboot.backend.tracing.store.TraceData;

public class QueryExtractor {

    private final SqlStatements sqlStatements;

    /** {@code sqlFormatter} is null when none is available; statements then carry no formatted SQL. */
    public QueryExtractor(MaskingEngine maskingEngine, SqlFormatter sqlFormatter) {
        this.sqlStatements = new SqlStatements(maskingEngine, sqlFormatter);
    }

    public List<QueryInfo> extract(TraceData traceData) {
        Map<String, Long> rowCounts = RowCounts.byQuerySpanId(traceData.spans());

        return traceData.spans().stream()
                .filter(DbSpans::isQuery)
                .map(span -> extractQuery(span, rowCounts.get(span.spanId())))
                .toList();
    }

    private QueryInfo extractQuery(SpanData span, Long rowCount) {
        String dbSystem = DbSpans.system(span.tags());

        Instant timestamp = span.startTime();
        long creationOrder = span.creationOrder();

        return new QueryInfo(
                span.spanId(), sqlStatements.of(span), dbSystem, span.durationMs(), timestamp, rowCount, creationOrder);
    }
}
