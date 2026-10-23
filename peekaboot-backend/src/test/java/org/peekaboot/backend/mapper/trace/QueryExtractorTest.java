package org.peekaboot.backend.mapper.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.Spans.jdbcQuery;
import static org.peekaboot.backend.testsupport.Spans.query;
import static org.peekaboot.backend.testsupport.Spans.resultSet;
import static org.peekaboot.backend.testsupport.Spans.span;

import io.micrometer.tracing.Span;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.trace.QueryInfo;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.testsupport.TraceDatas;
import org.peekaboot.backend.tracing.store.TraceData;

class QueryExtractorTest {

    private final QueryExtractor extractor = new QueryExtractor();
    private final SqlStatements sqlStatements = new SqlStatements(new MaskingEngine(), SqlFormatter.NONE);

    @Test
    void extract_shouldFindQueryWithDbStatementTag() {
        var querySpan = query("span1")
                .named("SELECT users")
                .at(0, 100)
                .tags(Map.of("db.statement", "SELECT * FROM users WHERE id = ?", "db.system", "postgresql"))
                .order(10)
                .build();

        var traceData = TraceDatas.of("trace1", querySpan);

        List<QueryInfo> queries = extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).spanId()).isEqualTo("span1");
        assertThat(queries.get(0).dbSystem()).isEqualTo("postgresql");
        assertThat(queries.get(0).durationMs()).isEqualTo(100);
    }

    @Test
    void extract_shouldFindQueryWithJdbcQueryTag() {
        var querySpan = jdbcQuery("span1", "INSERT INTO orders (user_id) VALUES (?)")
                .tag("peer.service", "orders_db")
                .order(20)
                .build();

        var traceData = TraceDatas.of("trace1", querySpan);

        List<QueryInfo> queries = extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).spanId()).isEqualTo("span1");
        assertThat(queries.get(0).dbSystem()).isEqualTo("orders_db");
    }

    /** A statement batch is one span carrying jdbc.query[0..N], so it lists as one query. */
    @Test
    void extract_shouldListABatchAsOneQuery() {
        var batchSpan = query("span1")
                .tags(Map.of(
                        "jdbc.query[10]", "INSERT INTO t VALUES (10)",
                        "jdbc.query[0]", "INSERT INTO t VALUES (0)",
                        "jdbc.query[2]", "INSERT INTO t VALUES (2)",
                        "peer.service", "orders_db"))
                .order(20)
                .build();

        List<QueryInfo> queries = extract(TraceDatas.of("trace1", batchSpan));

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).spanId()).isEqualTo("span1");
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

        List<QueryInfo> queries = extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).spanId()).isEqualTo("span1");
    }

    @Test
    void extract_shouldMatchResultSetToQuery() {
        var querySpan = jdbcQuery("query1", "SELECT * FROM users").order(10).build();

        var resultSetSpan = resultSet("rs1", 42)
                .tag("peer.service", "sample_app_db")
                .order(11)
                .build();

        var traceData = TraceDatas.of("trace1", querySpan, resultSetSpan);

        List<QueryInfo> queries = extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).rowCount()).isEqualTo(42L);
    }

    @Test
    void extract_shouldNotAttributeRowCountToEarlierQueryWithoutResultSet() {
        var update = jdbcQuery("q1", "UPDATE users SET active = true").order(10).build();
        var select = jdbcQuery("q2", "SELECT * FROM users").order(20).build();
        var rs = resultSet("rs1", 42)
                .tag("peer.service", "sample_app_db")
                .order(21)
                .build();

        var traceData = TraceDatas.of("trace1", update, select, rs);

        List<QueryInfo> queries = extract(traceData);

        assertThat(queries).hasSize(2);
        assertThat(queries.get(0).spanId()).isEqualTo("q1");
        assertThat(queries.get(0).rowCount()).isNull();
        assertThat(queries.get(1).rowCount()).isEqualTo(42L);
    }

    @Test
    void extract_shouldMatchMultipleResultSetsToQueries() {
        var query1 = jdbcQuery("q1", "SELECT * FROM users").order(10).build();
        var rs1 = resultSet("rs1", 10)
                .tag("peer.service", "sample_app_db")
                .order(11)
                .build();

        var query2 = jdbcQuery("q2", "SELECT * FROM orders").order(20).build();
        var rs2 = resultSet("rs2", 25)
                .tag("peer.service", "sample_app_db")
                .order(21)
                .build();

        var traceData = TraceDatas.of("trace1", query1, rs1, query2, rs2);

        List<QueryInfo> queries = extract(traceData);

        assertThat(queries).hasSize(2);
        assertThat(queries.get(0).spanId()).isEqualTo("q1");
        assertThat(queries.get(0).rowCount()).isEqualTo(10L);
        assertThat(queries.get(1).spanId()).isEqualTo("q2");
        assertThat(queries.get(1).rowCount()).isEqualTo(25L);
    }

    @Test
    void extract_shouldNotMatchResultSetToQueryIfCreationOrderIsLower() {
        var resultSetSpan = resultSet("rs1", 99)
                .tag("peer.service", "sample_app_db")
                .order(5)
                .build();
        var querySpan = jdbcQuery("q1", "SELECT * FROM users").order(10).build();

        var traceData = TraceDatas.of("trace1", resultSetSpan, querySpan);

        List<QueryInfo> queries = extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).rowCount()).isNull();
    }

    @Test
    void extract_shouldReturnNullRowCountWhenNoResultSet() {
        var querySpan = query("span1")
                .tags(Map.of("db.statement", "UPDATE users SET active = true", "db.system", "postgresql"))
                .order(10)
                .build();

        var traceData = TraceDatas.of("trace1", querySpan);

        List<QueryInfo> queries = extract(traceData);

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

        List<QueryInfo> queries = extract(traceData);

        assertThat(queries).isEmpty();
    }

    /**
     * A query span whose instrumentation recorded no statement at all still is a query - the
     * summary counts it and the Queries tab lists it, with nothing to show for its text.
     */
    @Test
    void extract_shouldListAQuerySpanWithoutAStatement() {
        var querySpan =
                query("span1").tags(Map.of("db.system", "postgresql")).order(10).build();

        List<QueryInfo> queries = extract(TraceDatas.of("trace1", querySpan));

        assertThat(queries).hasSize(1);
        assertThat(queries.getFirst().dbSystem()).isEqualTo("postgresql");
    }

    @Test
    void extract_shouldIgnoreTheServerSideOfADbTaggedExchange() {
        var serverSpan = span("span1")
                .kind(Span.Kind.SERVER)
                .tags(Map.of("db.statement", "SELECT 1"))
                .build();

        List<QueryInfo> queries = extract(TraceDatas.of("trace1", serverSpan));

        assertThat(queries).isEmpty();
    }

    @Test
    void extract_shouldListQueriesInCreationOrder() {
        var query1 = jdbcQuery("q1", "SELECT 1").order(10).build();
        var query2 = jdbcQuery("q2", "SELECT 2").order(20).build();
        var query3 = jdbcQuery("q3", "SELECT 3").order(30).build();

        var traceData = TraceDatas.of("trace1", query1, query2, query3);

        List<QueryInfo> queries = extract(traceData);

        assertThat(queries).hasSize(3);
        assertThat(queries).extracting(QueryInfo::spanId).containsExactly("q1", "q2", "q3");
    }

    @Test
    void extract_shouldFindDbSystemFromDatasourceName() {
        var querySpan = query("span1")
                .tags(Map.of("jdbc.query[0]", "SELECT 1", "jdbc.datasource.name", "primary_db"))
                .order(10)
                .build();

        var traceData = TraceDatas.of("trace1", querySpan);

        List<QueryInfo> queries = extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).dbSystem()).isEqualTo("primary_db");
    }

    /** Two DataSources on one engine share a system; the name is what tells their queries apart. */
    @Test
    void extract_shouldCarryTheDataSourceNameBesideTheSystem() {
        var querySpan = query("span1")
                .tags(Map.of(
                        "db.query.text", "SELECT 1",
                        "db.system", "postgresql",
                        "jdbc.datasource.pool", "orders-db",
                        "jdbc.datasource.name", "catalog-uuid"))
                .order(10)
                .build();

        List<QueryInfo> queries = extract(TraceDatas.of("trace1", querySpan));

        assertThat(queries).singleElement().satisfies(query -> {
            assertThat(query.dbSystem()).isEqualTo("postgresql");
            assertThat(query.dataSourceName()).isEqualTo("orders-db");
        });
    }

    @Test
    void extract_shouldFallBackToTheDatasourceProxyNameWithoutAPool() {
        var querySpan = query("span1")
                .tags(Map.of("db.query.text", "SELECT 1", "jdbc.datasource.name", "orders-db"))
                .order(10)
                .build();

        List<QueryInfo> queries = extract(TraceDatas.of("trace1", querySpan));

        assertThat(queries)
                .singleElement()
                .extracting(QueryInfo::dataSourceName)
                .isEqualTo("orders-db");
    }

    @Test
    void extract_shouldIgnoreHikarisGeneratedPoolName() {
        var querySpan = query("span1")
                .tags(Map.of(
                        "db.query.text", "SELECT 1",
                        "jdbc.datasource.pool", "HikariPool-1",
                        "jdbc.datasource.name", "orders-db"))
                .order(10)
                .build();

        List<QueryInfo> queries = extract(TraceDatas.of("trace1", querySpan));

        assertThat(queries)
                .singleElement()
                .extracting(QueryInfo::dataSourceName)
                .isEqualTo("orders-db");
    }

    @Test
    void extract_shouldLeaveTheDataSourceNameNullForAGeneratedPoolNameAlone() {
        var querySpan = query("span1")
                .tags(Map.of("db.query.text", "SELECT 1", "jdbc.datasource.pool", "HikariPool-1"))
                .order(10)
                .build();

        List<QueryInfo> queries = extract(TraceDatas.of("trace1", querySpan));

        assertThat(queries)
                .singleElement()
                .extracting(QueryInfo::dataSourceName)
                .isNull();
    }

    @Test
    void extract_shouldLeaveTheDataSourceNameNullWhenNoTagNamesOne() {
        var querySpan = query("span1")
                .tags(Map.of("db.query.text", "SELECT 1", "db.system", "postgresql"))
                .order(10)
                .build();

        List<QueryInfo> queries = extract(TraceDatas.of("trace1", querySpan));

        assertThat(queries)
                .singleElement()
                .extracting(QueryInfo::dataSourceName)
                .isNull();
    }

    @Test
    void extract_shouldHandleTraceWithEmptySpans() {
        var traceData = new TraceData("trace1", null, null, null, List.of(), Set.of(), false);
        List<QueryInfo> queries = extract(traceData);
        assertThat(queries).isEmpty();
    }

    @Test
    void extract_shouldReturnNullRowCountWhenRowCountIsMalformed() {
        var querySpan = jdbcQuery("q1", "SELECT * FROM users").order(10).build();
        var resultSetSpan = span("rs1")
                .named("result-set")
                .kind(Span.Kind.CLIENT)
                .at(1100, 5)
                .tags(Map.of("jdbc.row-count", "not-a-number", "peer.service", "sample_app_db"))
                .order(11)
                .build();

        var traceData = TraceDatas.of("trace1", querySpan, resultSetSpan);

        List<QueryInfo> queries = extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).rowCount()).isNull();
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

        List<QueryInfo> queries = extract(traceData);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).dbSystem()).isEqualTo("postgresql");
    }

    private List<QueryInfo> extract(TraceData traceData) {
        return extractor.extract(traceData, new TraceQueries(traceData, sqlStatements));
    }
}
