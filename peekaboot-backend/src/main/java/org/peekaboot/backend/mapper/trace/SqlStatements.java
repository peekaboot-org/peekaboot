package org.peekaboot.backend.mapper.trace;

import java.util.List;
import java.util.Map;
import org.peekaboot.backend.config.PeekabootJson;
import org.peekaboot.backend.domain.trace.SqlStatement;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.tracing.store.SpanData;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;

/**
 * Builds the statement a query span carries, for the span tree ({@link TraceTreeMapper}) and
 * the Queries tab ({@link QueryExtractor}) alike, so a query reads the same in both. The raw
 * SQL is formatted before it is masked, so masking sees the values it sees in the raw text;
 * each bind parameter is masked the way the SQL is, by value pattern only.
 */
final class SqlStatements {

    private static final TypeReference<List<List<String>>> PARAMETER_SETS = new TypeReference<>() {};

    private final MaskingEngine maskingEngine;
    private final SqlFormatter sqlFormatter;

    /** {@code sqlFormatter} is null when none is available; every statement's {@code formatted} is null then. */
    SqlStatements(MaskingEngine maskingEngine, SqlFormatter sqlFormatter) {
        this.maskingEngine = maskingEngine;
        this.sqlFormatter = sqlFormatter;
    }

    /** The statement {@code span} carries; null for a span that is no query or recorded no SQL. */
    SqlStatement of(SpanData span) {
        String sql = DbSpans.isQuery(span) ? DbSpans.sql(span) : null;
        if (sql == null) {
            return null;
        }
        return new SqlStatement(
                maskingEngine.maskValue(sql), maskingEngine.maskValue(formatted(sql)), maskedParameters(span.tags()));
    }

    private String formatted(String sql) {
        if (sqlFormatter == null) {
            return null;
        }
        try {
            return sqlFormatter.format(sql);
        } catch (RuntimeException e) {
            // a formatter that chokes on odd SQL must not cost the trace its query
            return null;
        }
    }

    private List<List<String>> maskedParameters(Map<String, String> tags) {
        String json = tags == null ? null : tags.get(DbSpans.PARAMETERS_TAG);
        if (json == null) {
            return List.of();
        }
        List<List<String>> sets;
        try {
            sets = PeekabootJson.MAPPER.readValue(json, PARAMETER_SETS);
        } catch (JacksonException e) {
            return List.of();
        }
        if (sets == null || sets.stream().anyMatch(set -> set == null || set.contains(null))) {
            return List.of();
        }
        return sets.stream()
                .map(set -> set.stream().map(maskingEngine::maskValue).toList())
                .toList();
    }
}
