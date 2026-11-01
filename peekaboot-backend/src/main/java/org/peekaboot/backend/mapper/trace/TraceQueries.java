package org.peekaboot.backend.mapper.trace;

import java.util.HashMap;
import java.util.Map;
import org.peekaboot.backend.domain.trace.SqlStatement;
import org.peekaboot.backend.tracing.event.SpanData;
import org.peekaboot.backend.tracing.store.TraceData;

/**
 * Each query span's statement and row count in one trace. The span tree ({@link TraceTreeMapper})
 * and the Queries tab ({@link QueryExtractor}) both read both, so a trace detail pairs result sets
 * to their queries once. A statement is built the first time it is read and kept, so a subtree pays
 * only for its own queries and a detail formats each SQL once. Built by
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

    /** Null for a span that is no query or recorded no SQL. Built once per span, since the tree and the query list both serve it. */
    SqlStatement statement(SpanData span) {
        if (!statements.containsKey(span.spanId())) {
            statements.put(span.spanId(), sqlStatements.of(span));
        }
        return statements.get(span.spanId());
    }

    /** Null for a span that is no query or whose result set reported no count. */
    Long rowCount(String spanId) {
        return rowCounts.get(spanId);
    }
}
