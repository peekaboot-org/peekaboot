package org.peekaboot.backend.mapper.trace;

import io.micrometer.tracing.Span;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    private static final String CLIENT_KIND = Span.Kind.CLIENT.name();

    private DbSpans() {}

    public static boolean isQuery(SpanData span) {
        return span.kind() == Span.Kind.CLIENT && hasQueryTag(span.tags());
    }

    public static boolean isQuery(SpanNode span) {
        return CLIENT_KIND.equals(span.kind()) && hasQueryTag(span.tags());
    }

    /**
     * The statement a query span carries, unmasked: {@code db.query.text} (the current
     * OpenTelemetry convention) ahead of {@code db.statement} (its superseded spelling), then
     * datasource-proxy's {@code jdbc.query[N]}, then the span's own name when it looks like
     * SQL. Null when the instrumentation recorded no statement at all.
     */
    public static String sql(SpanData span) {
        Map<String, String> tags = span.tags();
        if (tags != null) {
            String sql = tags.get("db.query.text");
            if (sql == null) {
                sql = tags.get("db.statement");
            }
            if (sql == null) {
                sql = tags.entrySet().stream()
                        .filter(entry -> entry.getKey().startsWith("jdbc.query["))
                        .map(Map.Entry::getValue)
                        .findFirst()
                        .orElse(null);
            }
            if (sql != null) {
                return sql;
            }
        }
        return isSqlShaped(span.name()) ? span.name() : null;
    }

    private static boolean hasQueryTag(Map<String, ?> tags) {
        return tags != null && hasQueryTag(tags.keySet());
    }

    private static boolean hasQueryTag(Set<String> keys) {
        return keys.stream().anyMatch(key -> key.startsWith("db.") || key.startsWith("jdbc.query"));
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
