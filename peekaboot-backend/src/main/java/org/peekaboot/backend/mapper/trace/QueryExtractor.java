package org.peekaboot.backend.mapper.trace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.peekaboot.backend.domain.trace.QueryInfo;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.tracing.store.SpanData;
import org.peekaboot.backend.tracing.store.TraceData;
import org.springframework.stereotype.Component;

@Component
public class QueryExtractor {

    private final MaskingEngine maskingEngine = new MaskingEngine();

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

        // Query spans in creation order, so each query's row-count search can
        // be bounded by the next query
        List<SpanData> querySpans =
                sortedSpans.stream().filter(DbSpans::isQuery).toList();

        List<QueryInfo> queries = new ArrayList<>();
        for (int i = 0; i < querySpans.size(); i++) {
            long nextQueryOrder =
                    i + 1 < querySpans.size() ? querySpans.get(i + 1).creationOrder() : Long.MAX_VALUE;
            queries.add(extractQuery(querySpans.get(i), resultSets, nextQueryOrder));
        }

        return queries;
    }

    private QueryInfo extractQuery(SpanData span, List<ResultSetInfo> resultSets, long nextQueryOrder) {
        // Value patterns only - not column-aware literal masking. Parsing SQL to find
        // which literal belongs to a "password" column is a much larger, more
        // error-prone job; captured traces are documented as containing plaintext SQL.
        // This still catches a JWT, an AWS key, a PEM block or a credential-bearing URL
        // pasted into the statement.
        String sql = maskingEngine.maskValue(DbSpans.sql(span));

        String dbSystem = findDbSystem(span.tags());

        Instant timestamp = span.startTime();
        long durationMs = span.duration() != null ? span.duration().toMillis() : 0L;
        long creationOrder = span.creationOrder();

        // Find matching result-set for row count: it must come after this
        // query but before the next one
        Long rowCount = findRowCount(creationOrder, nextQueryOrder, resultSets);

        return new QueryInfo(span.spanId(), sql, dbSystem, durationMs, timestamp, rowCount, creationOrder);
    }

    private boolean isResultSetSpan(SpanData span) {
        return "result-set".equals(span.name())
                && span.tags() != null
                && span.tags().containsKey("jdbc.row-count");
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

    private Long findRowCount(long queryCreationOrder, long nextQueryOrder, List<ResultSetInfo> resultSets) {
        // First result-set created between this query and the next one
        for (ResultSetInfo rs : resultSets) {
            if (rs.creationOrder > queryCreationOrder && rs.creationOrder < nextQueryOrder) {
                return rs.rowCount;
            }
        }
        return null;
    }

    private String findDbSystem(Map<String, String> tags) {
        // 1. Current OpenTelemetry semantic convention, ahead of db.system, which is the
        // same convention's superseded spelling: when a library emits both, the current
        // one is authoritative.
        String system = tags.get("db.system.name");
        if (system != null) {
            return system;
        }

        // 2. Superseded OpenTelemetry convention
        system = tags.get("db.system");
        if (system != null) {
            return system;
        }

        // 3. datasource-proxy: jdbc.datasource.name or peer.service
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
