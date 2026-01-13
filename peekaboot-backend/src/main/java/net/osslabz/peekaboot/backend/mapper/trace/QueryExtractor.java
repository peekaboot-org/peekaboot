package net.osslabz.peekaboot.backend.mapper.trace;

import net.osslabz.peekaboot.backend.domain.trace.QueryInfo;
import net.osslabz.peekaboot.backend.tracing.store.SpanData;
import net.osslabz.peekaboot.backend.tracing.store.TraceData;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
public class QueryExtractor {

    public List<QueryInfo> extract(TraceData traceData) {
        if (traceData == null || traceData.spans() == null) {
            return List.of();
        }

        List<SpanData> spans = traceData.spans();

        // Sort spans by creationOrder for proper matching
        List<SpanData> sortedSpans = spans.stream()
                .sorted(Comparator.comparingLong(SpanData::creationOrder))
                .toList();

        // Collect result-set spans for row count matching
        List<ResultSetInfo> resultSets = sortedSpans.stream()
                .filter(this::isResultSetSpan)
                .map(s -> new ResultSetInfo(s.creationOrder(), extractRowCount(s)))
                .toList();

        List<QueryInfo> queries = new ArrayList<>();
        for (SpanData span : sortedSpans) {
            QueryInfo query = extractQuery(span, resultSets);
            if (query != null) {
                queries.add(query);
            }
        }

        // Sort queries by creationOrder
        return queries.stream()
                .sorted(Comparator.comparingLong(QueryInfo::creationOrder))
                .toList();
    }

    private QueryInfo extractQuery(SpanData span, List<ResultSetInfo> resultSets) {
        Map<String, String> tags = span.tags();
        if (tags == null || tags.isEmpty()) {
            return null;
        }

        // Look for SQL in various tag patterns
        String sql = findSql(tags, span.name());
        if (sql == null) {
            return null;
        }

        // Get database system from various sources
        String dbSystem = findDbSystem(tags);

        Instant timestamp = span.startTime();
        long durationMs = span.duration() != null ? span.duration().toMillis() : 0L;
        long creationOrder = span.creationOrder();

        // Find matching result-set for row count
        // Result-set span should come after the query span (creationOrder > query's creationOrder)
        Long rowCount = findRowCount(creationOrder, resultSets);

        return new QueryInfo(
                span.spanId(),
                sql,
                dbSystem,
                durationMs,
                timestamp,
                rowCount,
                creationOrder
        );
    }

    private boolean isResultSetSpan(SpanData span) {
        return "result-set".equals(span.name()) &&
               span.tags() != null &&
               span.tags().containsKey("jdbc.row-count");
    }

    private Long extractRowCount(SpanData span) {
        String rowCountStr = span.tags().get("jdbc.row-count");
        if (rowCountStr != null) {
            try {
                return Long.parseLong(rowCountStr);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private Long findRowCount(long queryCreationOrder, List<ResultSetInfo> resultSets) {
        // Find the first result-set that comes after this query
        // (result-set creationOrder > query creationOrder)
        for (ResultSetInfo rs : resultSets) {
            if (rs.creationOrder > queryCreationOrder) {
                return rs.rowCount;
            }
        }
        return null;
    }

    private String findSql(Map<String, String> tags, String spanName) {
        // 1. Standard OpenTelemetry: db.statement
        String sql = tags.get("db.statement");
        if (sql != null) {
            return sql;
        }

        // 2. datasource-proxy/micrometer: jdbc.query[0], jdbc.query[1], etc.
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            if (entry.getKey().startsWith("jdbc.query[")) {
                return entry.getValue();
            }
        }

        // 3. Span name if it looks like SQL
        if (spanName != null) {
            String upper = spanName.toUpperCase();
            if (upper.startsWith("SELECT ") || upper.startsWith("INSERT ") ||
                upper.startsWith("UPDATE ") || upper.startsWith("DELETE ")) {
                return spanName;
            }
        }

        return null;
    }

    private String findDbSystem(Map<String, String> tags) {
        // 1. Standard OpenTelemetry: db.system
        String system = tags.get("db.system");
        if (system != null) {
            return system;
        }

        // 2. datasource-proxy: jdbc.datasource.name or peer.service
        system = tags.get("jdbc.datasource.name");
        if (system != null) {
            return system;
        }

        system = tags.get("peer.service");
        if (system != null) {
            return system;
        }

        return null;
    }

    private record ResultSetInfo(long creationOrder, Long rowCount) {}
}
