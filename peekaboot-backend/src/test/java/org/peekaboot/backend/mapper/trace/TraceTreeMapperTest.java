package org.peekaboot.backend.mapper.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.Spans.asyncTask;
import static org.peekaboot.backend.testsupport.Spans.jdbcConnection;
import static org.peekaboot.backend.testsupport.Spans.span;

import io.micrometer.tracing.Span;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.analysis.DbSpans;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.peekaboot.backend.domain.trace.TraceSummary;
import org.peekaboot.backend.domain.trace.TraceTree;
import org.peekaboot.backend.testsupport.TraceDatas;

/** The counts, the type and the request {@link TraceTreeMapper} reads off a trace for its summary. */
class TraceTreeMapperTest extends TraceTreeMapperTestBase {

    @Test
    void calculatesTheTraceSummary() {
        // A trace with DB queries and HTTP calls
        var root = span("root")
                .named("GET /api/users")
                .kind(Span.Kind.SERVER)
                .at(0, 200)
                .tags(Map.of("http.method", "GET"))
                .build();
        var dbQuery1 = span("db1")
                .parent("root")
                .named("SELECT users")
                .kind(Span.Kind.CLIENT)
                .at(10, 50)
                .tags(Map.of("db.system", "postgresql"))
                .build();
        var dbQuery2 = span("db2")
                .parent("root")
                .named("SELECT roles")
                .kind(Span.Kind.CLIENT)
                .at(70, 30)
                .tags(Map.of("db.type", "sql"))
                .build();
        var httpCall = span("http1")
                .parent("root")
                .named("GET /external")
                .kind(Span.Kind.CLIENT)
                .at(110, 60)
                .tags(Map.of("http.url", "http://external.com"))
                .build();

        var traceData = TraceDatas.of("trace1", root, dbQuery1, dbQuery2, httpCall);
        TraceTree result = map(mapper, traceData);
        assertThat(result.spanCount()).isEqualTo(4);
        assertThat(result.queries().count()).isEqualTo(2);
        assertThat(result.queries().totalDurationMs()).isEqualTo(80L); // 50 + 30
    }

    @Test
    void calculatesTheTraceSummaryFromJdbcQueryTags() {
        // A trace with datasource-proxy/Micrometer style jdbc.query tags
        var root = span("root")
                .named("http get /")
                .kind(Span.Kind.SERVER)
                .at(0, 100)
                .tags(Map.of("http.method", "GET"))
                .build();
        var connection = jdbcConnection("conn").parent("root").at(10, 50).build();
        var query = span("query")
                .parent("root")
                .named("query")
                .kind(Span.Kind.CLIENT)
                .at(20, 30)
                .tags(Map.of("jdbc.query[0]", "SELECT * FROM users"))
                .build();
        var resultSet = span("rs")
                .parent("root")
                .named("result-set")
                .kind(Span.Kind.CLIENT)
                .at(55, 5)
                .tags(Map.of("jdbc.row-count", "10"))
                .build();

        var traceData = TraceDatas.of("trace1", root, connection, query, resultSet);
        TraceTree result = map(mapper, traceData);

        // Only the query span with jdbc.query* tag should be counted, not connection or result-set
        assertThat(result.queries().count()).isEqualTo(1);
        assertThat(result.queries().totalDurationMs()).isEqualTo(30L);
    }

    /**
     * One predicate, {@link DbSpans#isQuery}, decides what a query is for the summary count,
     * for the Queries tab and for the issue detector's per-span children count - so a trace
     * mixing every span shape the JDBC instrumentations emit reports one number, not three.
     */
    @Test
    void countsExactlyTheSpansTheQueriesTabListsAndTheIssueDetectorInspects() {
        var root = span("root")
                .named("GET /orders")
                .kind(Span.Kind.SERVER)
                .at(0, 200)
                .build();
        var otelQuery = span("q1")
                .parent("root")
                .named("SELECT orders")
                .kind(Span.Kind.CLIENT)
                .at(10, 20)
                .tags(Map.of("db.query.text", "select * from orders", "db.system.name", "h2"))
                .build();
        var proxyQuery = span("q2")
                .parent("root")
                .named("query")
                .kind(Span.Kind.CLIENT)
                .at(40, 20)
                .tags(Map.of("jdbc.query[0]", "select * from lines"))
                .build();
        var untaggedStatement = span("q3")
                .parent("root")
                .named("SELECT users")
                .kind(Span.Kind.CLIENT)
                .at(70, 20)
                .tags(Map.of("db.system", "h2"))
                .build();
        var connection = jdbcConnection("conn").parent("root").at(5, 100).build();
        var resultSet = span("rs")
                .parent("root")
                .named("result-set")
                .kind(Span.Kind.CLIENT)
                .at(60, 5)
                .tags(Map.of("jdbc.row-count", "3"))
                .build();
        var serverWithDbTag = span("srv")
                .parent("root")
                .named("handle")
                .kind(Span.Kind.SERVER)
                .at(90, 5)
                .tags(Map.of("db.statement", "SELECT 1"))
                .build();
        var httpCall = span("http")
                .parent("root")
                .named("GET /ext")
                .kind(Span.Kind.CLIENT)
                .at(100, 50)
                .tags(Map.of("http.url", "http://x"))
                .build();
        var traceData = TraceDatas.of(
                "trace1",
                root,
                otelQuery,
                proxyQuery,
                untaggedStatement,
                connection,
                resultSet,
                serverWithDbTag,
                httpCall);

        TraceTree result = map(mapper, traceData);
        int queriesListed = new QueryExtractor()
                .extract(traceData, mapper.served(traceData).queries())
                .size();
        long queryNodes =
                result.rootSpan().children().stream().filter(DbSpans::isQuery).count();

        assertThat(result.queries().count()).isEqualTo(3);
        assertThat(queriesListed).isEqualTo(result.queries().count());
        assertThat(queryNodes).isEqualTo(result.queries().count());
    }

    /**
     * The span and error counts cover async spans too, so a failure in background work keeps
     * the trace's status consistent with its place in the Errors bucket.
     */
    @Test
    void theSpanCountIncludesAsyncSpans() {
        var root = span("root").kind(Span.Kind.SERVER).at(0, 50).build();
        var async = asyncTask("async").parent("root").at(40, 240_000).build();

        var traceData = TraceDatas.of("trace1", root, async);

        TraceTree result = map(mapper, traceData);

        assertThat(result.spanCount()).isEqualTo(2);
    }

    /** {@code RootActionTypesTest} holds the classification itself. */
    @Test
    void theTreeCarriesTheTypeItsRootClassifiesAs() {
        var rootSpan = span("root")
                .named("task orderReconciler.reconcileOrders")
                .at(0, 100)
                .tags(Map.of(
                        "code.function", "reconcileOrders",
                        "code.namespace", "org.peekaboot.testingapp.order.OrderReconciler"))
                .build();

        TraceTree result = map(mapper, TraceDatas.of("trace1", rootSpan));

        assertThat(result.rootActionType()).isEqualTo(RootActionType.SCHEDULED_JOB);
    }

    /**
     * The names Spring Boot's own server-request observation puts on the root span - the
     * only ones a default Boot application ever produces. {@code uri} is the route pattern;
     * {@code http.url} carries the request URI and is the path worth showing.
     */
    @Test
    void extractsRequestSummaryFromSpringsDefaultObservationTags() {
        var root = span("root")
                .named("http get /api/users/{id}")
                .kind(Span.Kind.SERVER)
                .at(0, 100)
                .tags(Map.of(
                        "method", "GET",
                        "uri", "/api/users/{id}",
                        "status", "200",
                        "outcome", "SUCCESS",
                        "exception", "none",
                        "http.url", "/api/users/42"))
                .build();

        var traceData = TraceDatas.of("trace1", root);

        TraceTree result = map(mapper, traceData);

        assertThat(result.request())
                .isEqualTo(new TraceSummary.Request("GET", "/api/users/{id}", "/api/users/42", 200));
        assertThat(result.rootActionType()).isEqualTo(RootActionType.HTTP_REQUEST);
    }

    @Test
    void theRequestNamesTheMatchedRoute() {
        var root = span("root")
                .named("http get /persons/{id}")
                .kind(Span.Kind.SERVER)
                .at(0, 10)
                .tag("http.request.method", "GET")
                .tag("http.route", "/persons/{id}")
                .tag("url.path", "/persons/7")
                .tag("http.response.status_code", "200")
                .build();

        TraceTree result = map(mapper, TraceDatas.of("trace1", root));

        assertThat(result.request()).isEqualTo(new TraceSummary.Request("GET", "/persons/{id}", "/persons/7", 200));
    }
}
