package org.peekaboot.backend.mapper.trace;

import io.micrometer.tracing.Span;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.tracing.store.SpanData;

/**
 * The one definition of "database query span", shared by the summary count
 * ({@link TraceTreeMapper}), the SLOW_QUERY and HIGH_QUERY_COUNT issues ({@link IssueDetector})
 * and the Queries tab ({@link QueryExtractor}), so the three numbers a trace reports about its
 * queries agree.
 *
 * <p>A query is the CLIENT side of a database call - the only side Peekaboot ever sees -
 * tagged either by the OpenTelemetry conventions ({@code db.*}) or by datasource-proxy
 * ({@code jdbc.query*}). {@code jdbc.*} alone is not enough: datasource-proxy's connection
 * and result-set spans carry {@code jdbc.datasource.name} and {@code jdbc.row-count} and are
 * not queries.
 */
public final class DbSpans {

    /** datasource-proxy's per-statement tag; a batch carries one per statement. */
    private static final Pattern BATCH_STATEMENT_TAG = Pattern.compile("jdbc\\.query\\[(\\d+)\\]");

    private DbSpans() {}

    public static boolean isQuery(SpanData span) {
        return isQuery(span.kind(), span.tags());
    }

    public static boolean isQuery(SpanNode span) {
        return isQuery(span.kind(), span.tags());
    }

    private static boolean isQuery(Span.Kind kind, Map<String, String> tags) {
        return kind == Span.Kind.CLIENT
                && tags != null
                && tags.keySet().stream().anyMatch(key -> key.startsWith("db.") || key.startsWith("jdbc.query"));
    }

    /**
     * The statement a query span carries, unmasked: {@code db.query.text} (the current
     * OpenTelemetry convention) ahead of {@code db.statement} (its superseded spelling), then
     * datasource-proxy's {@code jdbc.query[N]} tags - a batch's statements joined in index
     * order - then the span's own name when it looks like SQL. Null when the
     * instrumentation recorded no statement at all.
     */
    public static String sql(SpanData span) {
        Map<String, String> tags = span.tags();
        if (tags != null) {
            String sql = Tags.first(tags, "db.query.text", "db.statement");
            if (sql == null) {
                sql = batchStatements(tags);
            }
            if (sql != null) {
                return sql;
            }
        }
        return isSqlShaped(span.name()) ? span.name() : null;
    }

    /**
     * The database a query span names, with the same priority as {@link #sql}: the current
     * OpenTelemetry {@code db.system.name} ahead of its superseded spelling {@code db.system},
     * then datasource-proxy's datasource name or peer service. Null when nothing named one.
     */
    public static String system(Map<String, String> tags) {
        return Tags.first(tags, "db.system.name", "db.system", "jdbc.datasource.name", "peer.service");
    }

    private static String batchStatements(Map<String, String> tags) {
        Map<Integer, String> byIndex = new TreeMap<>();
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            Matcher matcher = BATCH_STATEMENT_TAG.matcher(entry.getKey());
            if (matcher.matches()) {
                byIndex.put(Integer.parseInt(matcher.group(1)), entry.getValue());
            }
        }
        return byIndex.isEmpty() ? null : String.join(";\n", byIndex.values());
    }

    private static boolean isSqlShaped(String name) {
        if (name == null) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.startsWith("SELECT ")
                || upper.startsWith("INSERT ")
                || upper.startsWith("UPDATE ")
                || upper.startsWith("DELETE ");
    }
}
