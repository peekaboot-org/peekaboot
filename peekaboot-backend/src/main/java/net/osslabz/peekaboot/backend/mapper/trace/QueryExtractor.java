package net.osslabz.peekaboot.backend.mapper.trace;

import io.micrometer.tracing.Span;
import net.osslabz.peekaboot.backend.domain.trace.QueryInfo;
import net.osslabz.peekaboot.tracing.store.SpanData;
import net.osslabz.peekaboot.tracing.store.TraceData;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class QueryExtractor {

    private static final String DB_STATEMENT = "db.statement";
    private static final String DB_SYSTEM = "db.system";

    public List<QueryInfo> extract(TraceData traceData) {
        if (traceData == null || traceData.spans() == null) {
            return List.of();
        }

        List<QueryInfo> queries = new ArrayList<>();
        for (SpanData span : traceData.spans()) {
            if (isDbSpan(span)) {
                QueryInfo query = extractQuery(span);
                if (query != null) {
                    queries.add(query);
                }
            }
        }
        return queries;
    }

    private boolean isDbSpan(SpanData span) {
        Map<String, String> tags = span.tags();
        if (tags == null || tags.isEmpty()) {
            return false;
        }

        // Has db.* attributes
        if (tags.keySet().stream().anyMatch(k -> k.startsWith("db."))) {
            return true;
        }

        // CLIENT span with SQL-like name
        if (span.kind() == Span.Kind.CLIENT) {
            String name = span.name();
            if (name != null) {
                String upper = name.toUpperCase();
                if (upper.startsWith("SELECT ") || upper.startsWith("INSERT ") ||
                    upper.startsWith("UPDATE ") || upper.startsWith("DELETE ") ||
                    upper.contains(" FROM ")) {
                    return true;
                }
            }
        }

        return false;
    }

    private QueryInfo extractQuery(SpanData span) {
        Map<String, String> tags = span.tags();

        String sql = tags != null ? tags.get(DB_STATEMENT) : null;
        String dbSystem = tags != null ? tags.get(DB_SYSTEM) : null;

        // If no explicit statement, use span name if it looks like SQL
        if (sql == null && span.name() != null) {
            String name = span.name();
            String upper = name.toUpperCase();
            if (upper.startsWith("SELECT ") || upper.startsWith("INSERT ") ||
                upper.startsWith("UPDATE ") || upper.startsWith("DELETE ")) {
                sql = name;
            }
        }

        // Skip if no SQL found
        if (sql == null) {
            return null;
        }

        Instant timestamp = span.startTime();
        long durationMs = span.duration() != null ? span.duration().toMillis() : 0L;

        return new QueryInfo(
                span.spanId(),
                sql,
                dbSystem,
                durationMs,
                timestamp
        );
    }
}
