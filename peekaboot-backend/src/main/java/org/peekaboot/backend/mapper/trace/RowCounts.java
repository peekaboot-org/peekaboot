package org.peekaboot.backend.mapper.trace;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.peekaboot.backend.tracing.store.SpanData;

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

    /** Row count by query span id; a query whose result set reported none is absent. */
    static Map<String, Long> byQuerySpanId(List<SpanData> spans) {
        List<SpanData> resultSets = spans.stream().filter(DbSpans::isResultSet).toList();
        List<SpanData> queries = spans.stream().filter(DbSpans::isQuery).toList();

        Map<String, Long> rowCounts = new HashMap<>();
        for (int i = 0; i < queries.size(); i++) {
            SpanData query = queries.get(i);
            long nextQueryOrder = i + 1 < queries.size() ? queries.get(i + 1).creationOrder() : Long.MAX_VALUE;
            Long rowCount = rowCountBetween(resultSets, query.creationOrder(), nextQueryOrder);
            if (rowCount != null) {
                rowCounts.put(query.spanId(), rowCount);
            }
        }
        return rowCounts;
    }

    private static Long rowCountBetween(List<SpanData> resultSets, long queryOrder, long nextQueryOrder) {
        return resultSets.stream()
                .filter(resultSet ->
                        resultSet.creationOrder() > queryOrder && resultSet.creationOrder() < nextQueryOrder)
                .findFirst()
                .map(DbSpans::rowCount)
                .orElse(null);
    }
}
