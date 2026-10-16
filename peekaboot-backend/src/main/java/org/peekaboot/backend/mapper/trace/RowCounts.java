package org.peekaboot.backend.mapper.trace;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.peekaboot.backend.tracing.store.SpanData;
import org.peekaboot.backend.tracing.store.TraceData;

/**
 * Pairs each query span with the row count of its result set - the one pairing behind both the
 * Spans tab's badge ({@link TraceTreeMapper}) and the Queries tab's ({@link QueryExtractor}), so
 * a query reports the same number wherever it is read.
 *
 * <p>datasource-proxy records the count on a separate {@code result-set} span instead of on the
 * query it belongs to, and names no query on it. Creation order is what ties the two together:
 * the result set belongs to the query recorded before it, so each query's search ends at the
 * next query.
 */
final class RowCounts {

    private RowCounts() {}

    /**
     * Row count by query span id; a query whose result set reported none is absent. One pass,
     * since {@code spans} come in creation order, as {@link TraceData#spans()} carries them.
     */
    static Map<String, Long> byQuerySpanId(List<SpanData> spans) {
        Map<String, Long> rowCounts = new HashMap<>();
        SpanData awaitingResultSet = null;
        for (SpanData span : spans) {
            if (DbSpans.isQuery(span)) {
                awaitingResultSet = span;
            } else if (awaitingResultSet != null && DbSpans.isResultSet(span)) {
                Long rowCount = DbSpans.rowCount(span);
                if (rowCount != null) {
                    rowCounts.put(awaitingResultSet.spanId(), rowCount);
                }
                awaitingResultSet = null;
            }
        }
        return rowCounts;
    }
}
