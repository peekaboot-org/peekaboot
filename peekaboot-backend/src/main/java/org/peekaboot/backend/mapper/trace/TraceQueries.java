package org.peekaboot.backend.mapper.trace;

import java.util.HashMap;
import java.util.Map;
import org.peekaboot.backend.domain.trace.SqlStatement;
import org.peekaboot.backend.tracing.store.SpanData;
import org.peekaboot.backend.tracing.store.TraceData;

/**
 * Each query span's statement and row count in one trace, worked out once for the span tree
 * ({@link TraceTreeMapper}) and the Queries tab ({@link QueryExtractor}) alike, so a trace
 * detail formats and masks its SQL once and both read the same values. A statement is built on
 * first read, so a subtree pays only for its own queries. Built by
 * {@link TraceTreeMapper#queriesOf}, for the one snapshot it was built from.
 */
public final class TraceQueries {

    private final SqlStatements sqlStatements;
    private final Map<String, Long> rowCounts;
    private final Map<String, SqlStatement> statements = new HashMap<>();

    TraceQueries(TraceData traceData, SqlStatements sqlStatements) {
        this.sqlStatements = sqlStatements;
        this.rowCounts = RowCounts.byQuerySpanId(traceData.spans());
    }

    /** Null for a span that is no query or recorded no SQL. */
    SqlStatement statement(SpanData span) {
        return statements.computeIfAbsent(span.spanId(), spanId -> sqlStatements.of(span));
    }

    /** Null for a span that is no query or whose result set reported no count. */
    Long rowCount(String spanId) {
        return rowCounts.get(spanId);
    }
}
