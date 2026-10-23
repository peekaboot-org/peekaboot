package org.peekaboot.backend.mapper.trace;

import java.util.Map;
import org.peekaboot.backend.domain.trace.SqlStatement;
import org.peekaboot.backend.tracing.store.SpanData;
import org.peekaboot.backend.tracing.store.TraceData;

/**
 * Each query span's statement and row count in one trace. The span tree ({@link TraceTreeMapper})
 * reads both and the Queries tab ({@link QueryExtractor}) the row counts, so a trace detail pairs
 * result sets to their queries once. A statement is built when the tree reads it, so a subtree pays
 * only for its own queries. Built by {@link TraceTreeMapper#queriesOf}, for the one snapshot it was
 * built from.
 */
public final class TraceQueries {

    private final SqlStatements sqlStatements;
    private final Map<String, Long> rowCounts;

    TraceQueries(TraceData traceData, SqlStatements sqlStatements) {
        this.sqlStatements = sqlStatements;
        this.rowCounts = RowCounts.byQuerySpanId(traceData.spans());
    }

    /** Null for a span that is no query or recorded no SQL. */
    SqlStatement statement(SpanData span) {
        return sqlStatements.of(span);
    }

    /** Null for a span that is no query or whose result set reported no count. */
    Long rowCount(String spanId) {
        return rowCounts.get(spanId);
    }
}
