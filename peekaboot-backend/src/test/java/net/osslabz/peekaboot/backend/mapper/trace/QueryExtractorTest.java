package net.osslabz.peekaboot.backend.mapper.trace;

import io.micrometer.tracing.Span;
import net.osslabz.peekaboot.backend.domain.trace.QueryInfo;
import net.osslabz.peekaboot.backend.tracing.store.SpanData;
import net.osslabz.peekaboot.backend.tracing.store.TraceData;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QueryExtractorTest {

    private final QueryExtractor extractor = new QueryExtractor();

    @Test
    void extract_shouldFindQueryWithDbStatementTag() {
        var querySpan = createSpan("span1", "SELECT users", 100,
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
        var querySpan = createSpan("span1", "query", 50,
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
        // Note: span must have at least one tag for the extractor to process it
        var querySpan = createSpan("span1", "SELECT * FROM products", 30,
                Map.of("peer.service", "db"), 30);

        var traceData = TraceData.fromSpans("trace1", List.of(querySpan));

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).sql()).isEqualTo("SELECT * FROM products");
    }

    @Test
    void extract_shouldMatchResultSetToQuery() {
        // Query span created first (creationOrder=10)
        var querySpan = createSpan("query1", "query", 50,
                Map.of("jdbc.query[0]", "SELECT * FROM users", "peer.service", "mydb"),
                10);

        // Result-set span created after query (creationOrder=11)
        var resultSetSpan = createSpan("rs1", "result-set", 5,
                Map.of("jdbc.row-count", "42", "peer.service", "mydb"),
                11);

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
        var update = createSpan("q1", "query", 10,
                Map.of("jdbc.query[0]", "UPDATE users SET active = true", "peer.service", "db"),
                10);
        var select = createSpan("q2", "query", 20,
                Map.of("jdbc.query[0]", "SELECT * FROM users", "peer.service", "db"),
                20);
        var rs = createSpan("rs1", "result-set", 5,
                Map.of("jdbc.row-count", "42", "peer.service", "db"),
                21);

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
        var query1 = createSpan("q1", "query", 50,
                Map.of("jdbc.query[0]", "SELECT * FROM users", "peer.service", "db"),
                10);
        var rs1 = createSpan("rs1", "result-set", 5,
                Map.of("jdbc.row-count", "10", "peer.service", "db"),
                11);

        // Second query + result set
        var query2 = createSpan("q2", "query", 30,
                Map.of("jdbc.query[0]", "SELECT * FROM orders", "peer.service", "db"),
                20);
        var rs2 = createSpan("rs2", "result-set", 5,
                Map.of("jdbc.row-count", "25", "peer.service", "db"),
                21);

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
        var resultSetSpan = createSpan("rs1", "result-set", 5,
                Map.of("jdbc.row-count", "99", "peer.service", "db"),
                5);
        var querySpan = createSpan("q1", "query", 50,
                Map.of("jdbc.query[0]", "SELECT * FROM users", "peer.service", "db"),
                10);

        var traceData = TraceData.fromSpans("trace1", List.of(resultSetSpan, querySpan));

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).rowCount()).isNull(); // No match
    }

    @Test
    void extract_shouldReturnNullRowCountWhenNoResultSet() {
        var querySpan = createSpan("span1", "query", 50,
                Map.of("db.statement", "UPDATE users SET active = true", "db.system", "postgresql"),
                10);

        var traceData = TraceData.fromSpans("trace1", List.of(querySpan));

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).rowCount()).isNull();
    }

    @Test
    void extract_shouldIgnoreNonQuerySpans() {
        var httpSpan = createSpan("span1", "GET /api/users", 200,
                Map.of("http.method", "GET", "http.url", "/api/users"),
                10);
        var internalSpan = createSpan("span2", "processUser", 50,
                Map.of("custom.tag", "value"),
                20);

        var traceData = TraceData.fromSpans("trace1", List.of(httpSpan, internalSpan));

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).isEmpty();
    }

    @Test
    void extract_shouldReturnQueriesSortedByCreationOrder() {
        // Add spans in reverse creation order
        var query3 = createSpan("q3", "query", 10,
                Map.of("jdbc.query[0]", "SELECT 3", "peer.service", "db"),
                30);
        var query1 = createSpan("q1", "query", 10,
                Map.of("jdbc.query[0]", "SELECT 1", "peer.service", "db"),
                10);
        var query2 = createSpan("q2", "query", 10,
                Map.of("jdbc.query[0]", "SELECT 2", "peer.service", "db"),
                20);

        var traceData = TraceData.fromSpans("trace1", List.of(query3, query1, query2));

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).hasSize(3);
        assertThat(queries.get(0).sql()).isEqualTo("SELECT 1");
        assertThat(queries.get(1).sql()).isEqualTo("SELECT 2");
        assertThat(queries.get(2).sql()).isEqualTo("SELECT 3");
    }

    @Test
    void extract_shouldFindDbSystemFromDatasourceName() {
        var querySpan = createSpan("span1", "query", 50,
                Map.of("jdbc.query[0]", "SELECT 1", "jdbc.datasource.name", "primary_db"),
                10);

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
        var querySpan = createSpan("span1", "SELECT abbreviated...", 50,
                Map.of("db.statement", "SELECT * FROM users WHERE id = ? AND active = true", "db.system", "mysql"),
                10);

        var traceData = TraceData.fromSpans("trace1", List.of(querySpan));

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).sql()).isEqualTo("SELECT * FROM users WHERE id = ? AND active = true");
    }

    @Test
    void extract_shouldReturnNullRowCountWhenRowCountIsMalformed() {
        var querySpan = createSpan("q1", "query", 50,
                Map.of("jdbc.query[0]", "SELECT * FROM users", "peer.service", "db"),
                10);
        var resultSetSpan = createSpan("rs1", "result-set", 5,
                Map.of("jdbc.row-count", "not-a-number", "peer.service", "db"),
                11);

        var traceData = TraceData.fromSpans("trace1", List.of(querySpan, resultSetSpan));

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).rowCount()).isNull();
    }

    private SpanData createSpan(String spanId, String name, long durationMs,
                                Map<String, String> tags, long creationOrder) {
        Instant start = Instant.EPOCH.plusMillis(creationOrder * 100);
        Instant end = start.plusMillis(durationMs);
        return new SpanData(
                "trace1", spanId, null, name, Span.Kind.CLIENT,
                start, end, Duration.ofMillis(durationMs),
                tags, List.of(), null, null, null, null, null, List.of(), creationOrder
        );
    }
}
