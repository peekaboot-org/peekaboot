package org.peekaboot.backend.mapper.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.Spans.span;

import io.micrometer.tracing.Span;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.trace.QueryInfo;
import org.peekaboot.backend.tracing.store.SpanData;
import org.peekaboot.backend.tracing.store.TraceData;

class QueryExtractorTest {

    private final QueryExtractor extractor = new QueryExtractor();

    @Test
    void extract_shouldFindQueryWithDbStatementTag() {
        var querySpan = createSpan(
                "span1",
                "SELECT users",
                100,
                Map.of("db.statement", "SELECT * FROM users WHERE id = ?", "db.system", "postgresql"),
                10);

        var traceData = TraceData.fromSpans("trace1", List.of(querySpan));

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).sql()).isEqualTo("SELECT * FROM users WHERE id = ?");
        assertThat(queries.get(0).dbSystem()).isEqualTo("postgresql");
        assertThat(queries.get(0).durationMs()).isEqualTo(100);
    }

    @Test
    void extract_shouldFindQueryWithJdbcQueryTag() {
        var querySpan = createSpan(
                "span1",
                "query",
                50,
                Map.of("jdbc.query[0]", "INSERT INTO orders (user_id) VALUES (?)", "peer.service", "orders_db"),
                20);

        var traceData = TraceData.fromSpans("trace1", List.of(querySpan));

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).sql()).isEqualTo("INSERT INTO orders (user_id) VALUES (?)");
        assertThat(queries.get(0).dbSystem()).isEqualTo("orders_db");
    }

    @Test
    void extract_shouldDetectSqlFromSpanName() {
        // a query span (db.* tagged) whose instrumentation put the statement in the name only
        var querySpan = createSpan("span1", "SELECT * FROM products", 30, Map.of("db.system", "postgresql"), 30);

        var traceData = TraceData.fromSpans("trace1", List.of(querySpan));

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).sql()).isEqualTo("SELECT * FROM products");
    }

    @Test
    void extract_shouldMatchResultSetToQuery() {
        // Query span created first (creationOrder=10)
        var querySpan = createSpan(
                "query1", "query", 50, Map.of("jdbc.query[0]", "SELECT * FROM users", "peer.service", "mydb"), 10);

        // Result-set span created after query (creationOrder=11)
        var resultSetSpan =
                createSpan("rs1", "result-set", 5, Map.of("jdbc.row-count", "42", "peer.service", "mydb"), 11);

        var traceData = TraceData.fromSpans("trace1", List.of(querySpan, resultSetSpan));

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).rowCount()).isEqualTo(42L);
        assertThat(queries.get(0).creationOrder()).isEqualTo(10L);
    }

    @Test
    void extract_shouldNotAttributeRowCountToEarlierQueryWithoutResultSet() {
        // An UPDATE produces no result-set span; the following SELECT's
        // result set must not be attributed to it.
        var update = createSpan(
                "q1", "query", 10, Map.of("jdbc.query[0]", "UPDATE users SET active = true", "peer.service", "db"), 10);
        var select =
                createSpan("q2", "query", 20, Map.of("jdbc.query[0]", "SELECT * FROM users", "peer.service", "db"), 20);
        var rs = createSpan("rs1", "result-set", 5, Map.of("jdbc.row-count", "42", "peer.service", "db"), 21);

        var traceData = TraceData.fromSpans("trace1", List.of(update, select, rs));

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).hasSize(2);
        assertThat(queries.get(0).sql()).isEqualTo("UPDATE users SET active = true");
        assertThat(queries.get(0).rowCount()).isNull();
        assertThat(queries.get(1).rowCount()).isEqualTo(42L);
    }

    @Test
    void extract_shouldMatchMultipleResultSetsToQueries() {
        // First query + result set
        var query1 =
                createSpan("q1", "query", 50, Map.of("jdbc.query[0]", "SELECT * FROM users", "peer.service", "db"), 10);
        var rs1 = createSpan("rs1", "result-set", 5, Map.of("jdbc.row-count", "10", "peer.service", "db"), 11);

        // Second query + result set
        var query2 = createSpan(
                "q2", "query", 30, Map.of("jdbc.query[0]", "SELECT * FROM orders", "peer.service", "db"), 20);
        var rs2 = createSpan("rs2", "result-set", 5, Map.of("jdbc.row-count", "25", "peer.service", "db"), 21);

        var traceData = TraceData.fromSpans("trace1", List.of(query1, rs1, query2, rs2));

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).hasSize(2);
        assertThat(queries.get(0).sql()).isEqualTo("SELECT * FROM users");
        assertThat(queries.get(0).rowCount()).isEqualTo(10L);
        assertThat(queries.get(1).sql()).isEqualTo("SELECT * FROM orders");
        assertThat(queries.get(1).rowCount()).isEqualTo(25L);
    }

    @Test
    void extract_shouldNotMatchResultSetToQueryIfCreationOrderIsLower() {
        // Result-set created before query (shouldn't match)
        var resultSetSpan = createSpan("rs1", "result-set", 5, Map.of("jdbc.row-count", "99", "peer.service", "db"), 5);
        var querySpan =
                createSpan("q1", "query", 50, Map.of("jdbc.query[0]", "SELECT * FROM users", "peer.service", "db"), 10);

        var traceData = TraceData.fromSpans("trace1", List.of(resultSetSpan, querySpan));

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).rowCount()).isNull(); // No match
    }

    @Test
    void extract_shouldReturnNullRowCountWhenNoResultSet() {
        var querySpan = createSpan(
                "span1",
                "query",
                50,
                Map.of("db.statement", "UPDATE users SET active = true", "db.system", "postgresql"),
                10);

        var traceData = TraceData.fromSpans("trace1", List.of(querySpan));

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).rowCount()).isNull();
    }

    @Test
    void extract_shouldIgnoreNonQuerySpans() {
        var httpSpan =
                createSpan("span1", "GET /api/users", 200, Map.of("http.method", "GET", "http.url", "/api/users"), 10);
        var internalSpan = createSpan("span2", "processUser", 50, Map.of("custom.tag", "value"), 20);
        // SQL-shaped name, but nothing marks it as a database span
        var sqlNamedSpan = createSpan("span3", "SELECT * FROM products", 30, Map.of("peer.service", "db"), 30);

        var traceData = TraceData.fromSpans("trace1", List.of(httpSpan, internalSpan, sqlNamedSpan));

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).isEmpty();
    }

    /**
     * A query span whose instrumentation recorded no statement at all still is a query - the
     * summary counts it and the Queries tab lists it, with nothing to show for its text.
     */
    @Test
    void extract_shouldListAQuerySpanWithoutAStatementWithNullSql() {
        var querySpan = createSpan("span1", "query", 30, Map.of("db.system", "postgresql"), 10);

        List<QueryInfo> queries = extractor.extract(TraceData.fromSpans("trace1", List.of(querySpan)));

        assertThat(queries).hasSize(1);
        assertThat(queries.getFirst().sql()).isNull();
        assertThat(queries.getFirst().dbSystem()).isEqualTo("postgresql");
    }

    @Test
    void extract_shouldIgnoreTheServerSideOfADbTaggedExchange() {
        var serverSpan = span("span1")
                .kind(Span.Kind.SERVER)
                .tags(Map.of("db.statement", "SELECT 1"))
                .build();

        List<QueryInfo> queries = extractor.extract(TraceData.fromSpans("trace1", List.of(serverSpan)));

        assertThat(queries).isEmpty();
    }

    @Test
    void extract_shouldReturnQueriesSortedByCreationOrder() {
        // Add spans in reverse creation order
        var query3 = createSpan("q3", "query", 10, Map.of("jdbc.query[0]", "SELECT 3", "peer.service", "db"), 30);
        var query1 = createSpan("q1", "query", 10, Map.of("jdbc.query[0]", "SELECT 1", "peer.service", "db"), 10);
        var query2 = createSpan("q2", "query", 10, Map.of("jdbc.query[0]", "SELECT 2", "peer.service", "db"), 20);

        var traceData = TraceData.fromSpans("trace1", List.of(query3, query1, query2));

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).hasSize(3);
        assertThat(queries.get(0).sql()).isEqualTo("SELECT 1");
        assertThat(queries.get(1).sql()).isEqualTo("SELECT 2");
        assertThat(queries.get(2).sql()).isEqualTo("SELECT 3");
    }

    @Test
    void extract_shouldFindDbSystemFromDatasourceName() {
        var querySpan = createSpan(
                "span1", "query", 50, Map.of("jdbc.query[0]", "SELECT 1", "jdbc.datasource.name", "primary_db"), 10);

        var traceData = TraceData.fromSpans("trace1", List.of(querySpan));

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).dbSystem()).isEqualTo("primary_db");
    }

    @Test
    void extract_shouldHandleNullTraceData() {
        List<QueryInfo> queries = extractor.extract(null);
        assertThat(queries).isEmpty();
    }

    @Test
    void extract_shouldHandleTraceWithNullSpans() {
        var traceData = new TraceData("trace1", null, null, null, 0, null);
        List<QueryInfo> queries = extractor.extract(traceData);
        assertThat(queries).isEmpty();
    }

    @Test
    void extract_shouldHandleTraceWithEmptySpans() {
        var traceData = new TraceData("trace1", null, null, null, 0, List.of());
        List<QueryInfo> queries = extractor.extract(traceData);
        assertThat(queries).isEmpty();
    }

    @Test
    void extract_shouldPreferDbStatementOverSpanName() {
        var querySpan = createSpan(
                "span1",
                "SELECT abbreviated...",
                50,
                Map.of("db.statement", "SELECT * FROM users WHERE id = ? AND active = true", "db.system", "mysql"),
                10);

        var traceData = TraceData.fromSpans("trace1", List.of(querySpan));

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).sql()).isEqualTo("SELECT * FROM users WHERE id = ? AND active = true");
    }

    @Test
    void extract_shouldReturnNullRowCountWhenRowCountIsMalformed() {
        var querySpan =
                createSpan("q1", "query", 50, Map.of("jdbc.query[0]", "SELECT * FROM users", "peer.service", "db"), 10);
        var resultSetSpan =
                createSpan("rs1", "result-set", 5, Map.of("jdbc.row-count", "not-a-number", "peer.service", "db"), 11);

        var traceData = TraceData.fromSpans("trace1", List.of(querySpan, resultSetSpan));

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).rowCount()).isNull();
    }

    /**
     * The engine's value patterns only, not column-aware SQL literal masking: no attempt
     * is made to identify which literal belongs to a "password" column, only high-precision
     * provider-shaped credentials (a JWT, an AWS key, a PEM block, a credential-bearing
     * URL) embedded in the SQL text.
     */
    @Test
    void extract_shouldMaskACredentialBearingUrlEmbeddedInSql() {
        var querySpan = createSpan(
                "span1",
                "query",
                20,
                Map.of(
                        "db.statement",
                        "INSERT INTO webhooks (callback_url) VALUES ('https://admin:hunter2@example.com/hook')",
                        "db.system",
                        "postgresql"),
                10);

        var traceData = TraceData.fromSpans("trace1", List.of(querySpan));

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).sql())
                .isEqualTo("INSERT INTO webhooks (callback_url) VALUES ('https://******@example.com/hook')");
    }

    @Test
    void extract_shouldLeaveOrdinarySqlWithNoEmbeddedCredentialUntouched() {
        var querySpan = createSpan(
                "span1",
                "query",
                20,
                Map.of("db.statement", "SELECT * FROM users WHERE email = ?", "db.system", "postgresql"),
                10);

        var traceData = TraceData.fromSpans("trace1", List.of(querySpan));

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries.get(0).sql()).isEqualTo("SELECT * FROM users WHERE email = ?");
    }

    /**
     * The tag set datasource-micrometer-opentelemetry actually emits. Its span name is an
     * abbreviated summary ("SELECT person"), so a fallback to the name silently under-shows
     * the statement - which is exactly the defect this covers.
     */
    @Test
    void extract_shouldPreferDbQueryTextOverASqlShapedSpanName() {
        var querySpan = createSpan(
                "span1",
                "SELECT person",
                100,
                Map.of(
                        "db.query.text", "select p1_0.id,p1_0.name from person p1_0",
                        "db.system.name", "postgresql"),
                10);

        var traceData = TraceData.fromSpans("trace1", List.of(querySpan));

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).sql()).isEqualTo("select p1_0.id,p1_0.name from person p1_0");
        assertThat(queries.get(0).dbSystem()).isEqualTo("postgresql");
    }

    @Test
    void extract_shouldPreferDbQueryTextOverDbStatementWhenBothArePresent() {
        var querySpan = createSpan(
                "span1",
                "query",
                40,
                Map.of(
                        "db.query.text", "SELECT * FROM current",
                        "db.statement", "SELECT * FROM legacy",
                        "db.system.name", "postgresql"),
                10);

        var traceData = TraceData.fromSpans("trace1", List.of(querySpan));

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).sql()).isEqualTo("SELECT * FROM current");
    }

    @Test
    void extract_shouldPreferDbSystemNameOverDbSystem() {
        var querySpan = createSpan(
                "span1",
                "query",
                40,
                Map.of(
                        "db.query.text", "SELECT 1",
                        "db.system.name", "postgresql",
                        "db.system", "other"),
                10);

        var traceData = TraceData.fromSpans("trace1", List.of(querySpan));

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).dbSystem()).isEqualTo("postgresql");
    }

    @Test
    void extract_shouldMaskACredentialEmbeddedInDbQueryText() {
        var querySpan = createSpan(
                "span1",
                "query",
                20,
                Map.of(
                        "db.query.text",
                        "INSERT INTO webhooks (callback_url) VALUES ('https://admin:hunter2@example.com/hook')",
                        "db.system.name",
                        "postgresql"),
                10);

        var traceData = TraceData.fromSpans("trace1", List.of(querySpan));

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).sql())
                .isEqualTo("INSERT INTO webhooks (callback_url) VALUES ('https://******@example.com/hook')");
    }

    private SpanData createSpan(
            String spanId, String name, long durationMs, Map<String, String> tags, long creationOrder) {
        return span(spanId)
                .named(name)
                .kind(Span.Kind.CLIENT)
                .at(creationOrder * 100, durationMs)
                .tags(tags)
                .order(creationOrder)
                .build();
    }
}
