package org.peekaboot.backend.mapper.trace;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.peekaboot.backend.domain.trace.QueryInfo;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.tracing.store.SpanData;
import org.peekaboot.backend.tracing.store.TraceData;

public class QueryExtractor {

    private final MaskingEngine maskingEngine;

    public QueryExtractor(MaskingEngine maskingEngine) {
        this.maskingEngine = maskingEngine;
    }

    public List<QueryInfo> extract(TraceData traceData) {
        Map<String, Long> rowCounts = RowCounts.byQuerySpanId(traceData.spans());

        return traceData.spans().stream()
                .filter(DbSpans::isQuery)
                .map(span -> extractQuery(span, rowCounts.get(span.spanId())))
                .toList();
    }

    private QueryInfo extractQuery(SpanData span, Long rowCount) {
        // value patterns only (MaskingEngine.maskValue), not column-aware literal masking
        String sql = maskingEngine.maskValue(DbSpans.sql(span));

        String dbSystem = DbSpans.system(span.tags());

        Instant timestamp = span.startTime();
        long creationOrder = span.creationOrder();

        return new QueryInfo(span.spanId(), sql, dbSystem, span.durationMs(), timestamp, rowCount, creationOrder);
    }
}
