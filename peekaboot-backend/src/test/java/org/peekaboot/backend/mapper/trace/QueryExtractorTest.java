package org.peekaboot.backend.mapper.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.Spans.query;
import static org.peekaboot.backend.testsupport.Spans.resultSet;
import static org.peekaboot.backend.testsupport.Spans.span;

import io.micrometer.tracing.Span;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.peekaboot.backend.domain.trace.QueryInfo;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.testsupport.TraceDatas;
import org.peekaboot.backend.tracing.store.TraceData;

class QueryExtractorTest {

    private final QueryExtractor extractor = new QueryExtractor(new MaskingEngine());

    @Test
    void extract_shouldFindQueryWithDbStatementTag() {
        var querySpan = query("span1")
                .named("SELECT users")
                .at(0, 100)
                .tags(Map.of("db.statement", "SELECT * FROM users WHERE id = ?", "db.system", "postgresql"))
                .order(10)
                .build();

        var traceData = TraceDatas.of("trace1", querySpan);

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).sql()).isEqualTo("SELECT * FROM users WHERE id = ?");
        assertThat(queries.get(0).dbSystem()).isEqualTo("postgresql");
        assertThat(queries.get(0).durationMs()).isEqualTo(100);
    }

    @Test
    void extract_shouldFindQueryWithJdbcQueryTag() {
        var querySpan = query("span1")
                .tags(Map.of("jdbc.query[0]", "INSERT INTO orders (user_id) VALUES (?)", "peer.service", "orders_db"))
                .order(20)
                .build();

        var traceData = TraceDatas.of("trace1", querySpan);

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).sql()).isEqualTo("INSERT INTO orders (user_id) VALUES (?)");
        assertThat(queries.get(0).dbSystem()).isEqualTo("orders_db");
    }

    /** A statement batch is one span carrying jdbc.query[0..N]; every statement belongs to it, in index order. */
    @Test
    void extract_shouldJoinTheStatementsOfABatchInIndexOrder() {
        var batchSpan = query("span1")
                .tags(Map.of(
                        "jdbc.query[10]", "INSERT INTO t VALUES (10)",
                        "jdbc.query[0]", "INSERT INTO t VALUES (0)",
                        "jdbc.query[2]", "INSERT INTO t VALUES (2)",
                        "peer.service", "orders_db"))
                .order(20)
                .build();

        List<QueryInfo> queries = extractor.extract(TraceDatas.of("trace1", batchSpan));

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).sql())
                .isEqualTo("INSERT INTO t VALUES (0);\nINSERT INTO t VALUES (2);\nINSERT INTO t VALUES (10)");
    }

    @Test
    void extract_shouldDetectSqlFromSpanName() {
        // a query span (db.* tagged) whose instrumentation put the statement in the name only
        var querySpan = span("span1")
                .named("SELECT * FROM products")
                .kind(Span.Kind.CLIENT)
                .at(3000, 30)
                .tags(Map.of("db.system", "postgresql"))
                .order(30)
                .build();

        var traceData = TraceDatas.of("trace1", querySpan);

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).sql()).isEqualTo("SELECT * FROM products");
    }

    @Test
    void extract_shouldMatchResultSetToQuery() {
        // Query span created first (creationOrder=10)
        var querySpan = query("query1")
                .tags(Map.of("jdbc.query[0]", "SELECT * FROM users", "peer.service", "mydb"))
                .order(10)
                .build();

        // Result-set span created after query (creationOrder=11)
        var resultSetSpan =
                resultSet("rs1", 42).tag("peer.service", "mydb").order(11).build();

        var traceData = TraceDatas.of("trace1", querySpan, resultSetSpan);

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).rowCount()).isEqualTo(42L);
        assertThat(queries.get(0).creationOrder()).isEqualTo(10L);
    }

    @Test
    void extract_shouldNotAttributeRowCountToEarlierQueryWithoutResultSet() {
        // An UPDATE produces no result-set span; the following SELECT's
        // result set must not be attributed to it.
        var update = query("q1")
                .tags(Map.of("jdbc.query[0]", "UPDATE users SET active = true", "peer.service", "db"))
                .order(10)
                .build();
        var select = query("q2")
                .tags(Map.of("jdbc.query[0]", "SELECT * FROM users", "peer.service", "db"))
                .order(20)
                .build();
        var rs = resultSet("rs1", 42).tag("peer.service", "db").order(21).build();

        var traceData = TraceDatas.of("trace1", update, select, rs);

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).hasSize(2);
        assertThat(queries.get(0).sql()).isEqualTo("UPDATE users SET active = true");
        assertThat(queries.get(0).rowCount()).isNull();
        assertThat(queries.get(1).rowCount()).isEqualTo(42L);
    }

    @Test
    void extract_shouldMatchMultipleResultSetsToQueries() {
        // First query + result set
        var query1 = query("q1")
                .tags(Map.of("jdbc.query[0]", "SELECT * FROM users", "peer.service", "db"))
                .order(10)
                .build();
        var rs1 = resultSet("rs1", 10).tag("peer.service", "db").order(11).build();

        // Second query + result set
        var query2 = query("q2")
                .tags(Map.of("jdbc.query[0]", "SELECT * FROM orders", "peer.service", "db"))
                .order(20)
                .build();
        var rs2 = resultSet("rs2", 25).tag("peer.service", "db").order(21).build();

        var traceData = TraceDatas.of("trace1", query1, rs1, query2, rs2);

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
        var resultSetSpan =
                resultSet("rs1", 99).tag("peer.service", "db").order(5).build();
        var querySpan = query("q1")
                .tags(Map.of("jdbc.query[0]", "SELECT * FROM users", "peer.service", "db"))
                .order(10)
                .build();

        var traceData = TraceDatas.of("trace1", resultSetSpan, querySpan);

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).rowCount()).isNull(); // No match
    }

    @Test
    void extract_shouldReturnNullRowCountWhenNoResultSet() {
        var querySpan = query("span1")
                .tags(Map.of("db.statement", "UPDATE users SET active = true", "db.system", "postgresql"))
                .order(10)
                .build();

        var traceData = TraceDatas.of("trace1", querySpan);

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).rowCount()).isNull();
    }

    @Test
    void extract_shouldIgnoreNonQuerySpans() {
        var httpSpan = span("span1")
                .named("GET /api/users")
                .kind(Span.Kind.CLIENT)
                .at(1000, 200)
                .tags(Map.of("http.method", "GET", "http.url", "/api/users"))
                .order(10)
                .build();
        var internalSpan = span("span2")
                .named("processUser")
                .kind(Span.Kind.CLIENT)
                .at(2000, 50)
                .tags(Map.of("custom.tag", "value"))
                .order(20)
                .build();
        // SQL-shaped name, but nothing marks it as a database span
        var sqlNamedSpan = span("span3")
                .named("SELECT * FROM products")
                .kind(Span.Kind.CLIENT)
                .at(3000, 30)
                .tags(Map.of("peer.service", "db"))
                .order(30)
                .build();

        var traceData = TraceDatas.of("trace1", httpSpan, internalSpan, sqlNamedSpan);

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).isEmpty();
    }

    /**
     * A query span whose instrumentation recorded no statement at all still is a query - the
     * summary counts it and the Queries tab lists it, with nothing to show for its text.
     */
    @Test
    void extract_shouldListAQuerySpanWithoutAStatementWithNullSql() {
        var querySpan =
                query("span1").tags(Map.of("db.system", "postgresql")).order(10).build();

        List<QueryInfo> queries = extractor.extract(TraceDatas.of("trace1", querySpan));

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

        List<QueryInfo> queries = extractor.extract(TraceDatas.of("trace1", serverSpan));

        assertThat(queries).isEmpty();
    }

    @Test
    void extract_shouldListQueriesInCreationOrder() {
        var query1 = query("q1")
                .tags(Map.of("jdbc.query[0]", "SELECT 1", "peer.service", "db"))
                .order(10)
                .build();
        var query2 = query("q2")
                .tags(Map.of("jdbc.query[0]", "SELECT 2", "peer.service", "db"))
                .order(20)
                .build();
        var query3 = query("q3")
                .tags(Map.of("jdbc.query[0]", "SELECT 3", "peer.service", "db"))
                .order(30)
                .build();

        var traceData = TraceDatas.of("trace1", query1, query2, query3);

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).hasSize(3);
        assertThat(queries.get(0).sql()).isEqualTo("SELECT 1");
        assertThat(queries.get(1).sql()).isEqualTo("SELECT 2");
        assertThat(queries.get(2).sql()).isEqualTo("SELECT 3");
    }

    @Test
    void extract_shouldFindDbSystemFromDatasourceName() {
        var querySpan = query("span1")
                .tags(Map.of("jdbc.query[0]", "SELECT 1", "jdbc.datasource.name", "primary_db"))
                .order(10)
                .build();

        var traceData = TraceDatas.of("trace1", querySpan);

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).dbSystem()).isEqualTo("primary_db");
    }

    @Test
    void extract_shouldHandleTraceWithEmptySpans() {
        var traceData = new TraceData("trace1", null, null, null, List.of(), false);
        List<QueryInfo> queries = extractor.extract(traceData);
        assertThat(queries).isEmpty();
    }

    @Test
    void extract_shouldReturnNullRowCountWhenRowCountIsMalformed() {
        var querySpan = query("q1")
                .tags(Map.of("jdbc.query[0]", "SELECT * FROM users", "peer.service", "db"))
                .order(10)
                .build();
        var resultSetSpan = span("rs1")
                .named("result-set")
                .kind(Span.Kind.CLIENT)
                .at(1100, 5)
                .tags(Map.of("jdbc.row-count", "not-a-number", "peer.service", "db"))
                .order(11)
                .build();

        var traceData = TraceDatas.of("trace1", querySpan, resultSetSpan);

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
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            INSERT INTO webhooks (callback_url) VALUES ('https://admin:hunter2@example.com/hook') | INSERT INTO webhooks (callback_url) VALUES ('https://******@example.com/hook')
            SELECT * FROM users WHERE email = ?                                                    | SELECT * FROM users WHERE email = ?
            """)
    void extract_shouldMaskOnlyProviderShapedCredentialsEmbeddedInSql(String statement, String expected) {
        var querySpan = query("span1")
                .tags(Map.of("db.statement", statement, "db.system", "postgresql"))
                .order(10)
                .build();

        List<QueryInfo> queries = extractor.extract(TraceDatas.of("trace1", querySpan));

        assertThat(queries).extracting(QueryInfo::sql).containsExactly(expected);
    }

    @Test
    void extract_shouldPreferDbSystemNameOverDbSystem() {
        var querySpan = query("span1")
                .tags(Map.of(
                        "db.query.text", "SELECT 1",
                        "db.system.name", "postgresql",
                        "db.system", "other"))
                .order(10)
                .build();

        var traceData = TraceDatas.of("trace1", querySpan);

        List<QueryInfo> queries = extractor.extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).dbSystem()).isEqualTo("postgresql");
    }
}
