package org.peekaboot.backend.mapper.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.peekaboot.backend.testsupport.Spans.jdbcConnection;
import static org.peekaboot.backend.testsupport.Spans.jdbcQuery;
import static org.peekaboot.backend.testsupport.Spans.resultSet;
import static org.peekaboot.backend.testsupport.Spans.span;

import io.micrometer.tracing.Span;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.trace.SpanExceptionInfo;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.SqlStatement;
import org.peekaboot.backend.domain.trace.TraceTree;
import org.peekaboot.backend.stacktrace.StackTraceFolding.Range;
import org.peekaboot.backend.stacktrace.StackTraceRules;
import org.peekaboot.backend.testsupport.TraceDatas;
import org.peekaboot.backend.tracing.event.SpanData;
import org.peekaboot.backend.tracing.store.TraceData;
import org.peekaboot.backend.tracing.tags.QueryParameterTags;

/** The tags, statement, row count, events and exception {@link TraceTreeMapper} puts on a span. */
class TraceTreeMapperSpanContentTest extends TraceTreeMapperTestBase {

    @Test
    void keepsTagsOnEachSpan() {
        // Parent span with two children, each with their own tags
        var parent = span("parent")
                .named("parent-op")
                .kind(Span.Kind.SERVER)
                .at(0, 100)
                .tags(Map.of("service.name", "api"))
                .build();
        var child1 = span("child1")
                .parent("parent")
                .named("child1-op")
                .kind(Span.Kind.CLIENT)
                .at(10, 30)
                .tags(Map.of("db.system", "postgresql", "db.name", "mydb"))
                .build();
        var child2 = span("child2")
                .parent("parent")
                .named("child2-op")
                .kind(Span.Kind.CLIENT)
                .at(50, 30)
                .tags(Map.of("db.system", "postgresql", "db.name", "mydb"))
                .build();

        var traceData = TraceDatas.of("trace1", parent, child1, child2);
        TraceTree result = map(mapper, traceData);

        // every span keeps its own tags
        SpanNode parentNode = result.rootSpan();
        assertThat(parentNode.tags()).containsEntry("service.name", "api");
        assertThat(parentNode.tags()).doesNotContainKey("db.system");

        // Children keep their tags
        for (SpanNode child : parentNode.children()) {
            assertThat(child.tags()).containsEntry("db.system", "postgresql");
            assertThat(child.tags()).containsEntry("db.name", "mydb");
        }
    }

    @Test
    void masksASensitiveShapedTagValue() {
        var root = span("root")
                .named("root-op")
                .kind(Span.Kind.SERVER)
                .at(0, 100)
                .tags(Map.of("http.request.header.authorization", "Bearer abc123"))
                .build();

        var traceData = TraceDatas.of("trace1", root);

        TraceTree result = map(mapper, traceData);

        assertThat(result.rootSpan().tags()).containsEntry("http.request.header.authorization", "******");
    }

    @Test
    void doesNotMaskOrdinaryTagsLikeHttpMethod() {
        var root = span("root")
                .named("root-op")
                .kind(Span.Kind.SERVER)
                .at(0, 100)
                .tags(Map.of("http.method", "GET", "http.status_code", "200"))
                .build();

        var traceData = TraceDatas.of("trace1", root);

        TraceTree result = map(mapper, traceData);

        assertThat(result.rootSpan().tags()).containsEntry("http.method", "GET");
        assertThat(result.rootSpan().tags()).containsEntry("http.status_code", "200");
    }

    @Test
    void appliesValuePatternRulesToATagValueUnderAnInnocuousKey() {
        var root = span("root")
                .named("root-op")
                .kind(Span.Kind.SERVER)
                .at(0, 100)
                .tags(Map.of("http.url", "https://admin:hunter2@example.com/api"))
                .build();

        var traceData = TraceDatas.of("trace1", root);

        TraceTree result = map(mapper, traceData);

        assertThat(result.rootSpan().tags().get("http.url")).isEqualTo("https://******@example.com/api");
    }

    @Test
    void putsTheMaskedStatementOnQuerySpansOnly() {
        var root = span("root")
                .named("GET /orders")
                .kind(Span.Kind.SERVER)
                .at(0, 200)
                .build();
        var query = span("q1")
                .parent("root")
                .named("query")
                .kind(Span.Kind.CLIENT)
                .at(10, 20)
                .tags(Map.of("db.query.text", "INSERT INTO hooks VALUES ('https://admin:hunter2@example.com/x')"))
                .build();
        var statementless = span("q2")
                .parent("root")
                .named("query")
                .kind(Span.Kind.CLIENT)
                .at(40, 20)
                .tags(Map.of("db.system", "h2"))
                .build();
        var connection = jdbcConnection("conn").parent("root").at(5, 100).build();

        TraceTree result = map(mapper, TraceDatas.of("trace1", root, query, statementless, connection));

        assertThat(result.rootSpan().statement()).isNull();
        assertThat(result.rootSpan().children())
                .extracting(SpanNode::spanId, TraceTreeMapperSpanContentTest::statementText)
                .containsExactly(
                        tuple("conn", null),
                        tuple("q1", "INSERT INTO hooks VALUES ('https://******@example.com/x')"),
                        tuple("q2", null));
    }

    // exception.message goes through the masker like tags do: a realistic exception
    // message can itself carry a credential, e.g. an HTTP client exception that echoes the
    // failing request's URL back with a query-string API key attached.
    @Test
    void masksACredentialEmbeddedInTheSpanErrorMessage() {
        var root =
                span("root").named("root-op").kind(Span.Kind.SERVER).at(0, 100).build();
        var errorSpan = span("error")
                .parent("root")
                .named("error-op")
                .kind(Span.Kind.CLIENT)
                .at(10, 50)
                .exception(
                        "org.springframework.web.client.HttpClientErrorException",
                        "HttpClientErrorException: 401 on GET \"https://api.x/v1?api_key=SECRET\"")
                .build();

        var traceData = TraceDatas.of("trace1", root, errorSpan);

        TraceTree result = map(mapper, traceData);

        SpanNode maskedErrorSpan = result.rootSpan().children().getFirst();
        assertThat(maskedErrorSpan.exception().message())
                .doesNotContain("SECRET")
                .contains("api_key=******");
    }

    /** A failed span's message and trace can echo a credential, and the dashboard folds them as it folds a log's. */
    @Test
    void servesAFailedSpansExceptionMaskedAndFolded() {
        SpanExceptionInfo exception = map(foldingMapper, TraceDatas.of("trace1", aRootThatFailedEchoingACredential()))
                .rootSpan()
                .exception();

        assertThat(exception.type()).isEqualTo("java.lang.IllegalStateException");
        assertThat(exception.message()).doesNotContain("SECRET").contains("api_key=******");
        assertThat(exception.stackTrace()).doesNotContain("SECRET").startsWith("java.lang.IllegalStateException");
        assertThat(exception.applicationFrames()).containsExactly(new Range(1, 2));
        assertThat(exception.hiddenFrames()).containsExactly(new Range(2, 4));
    }

    /** A summary's reasons and cause serve the message and the application frame, so the exception stays whole. */
    @Test
    void aSummaryMappingStillMasksAndFoldsAFailedSpansException() {
        TraceData traceData = TraceDatas.of("trace1", aRootThatFailedEchoingACredential());

        SpanExceptionInfo exception = foldingMapper
                .map(traceData, foldingMapper.forSummary())
                .rootSpan()
                .exception();

        assertThat(exception)
                .isNotNull()
                .isEqualTo(map(foldingMapper, traceData).rootSpan().exception());
    }

    /** String.lines() splits on a lone \r where the browser's split('\n') does not; the ranges must index what the wire carries. */
    @Test
    void anExceptionMessageCarryingACarriageReturnKeepsItsRangesOnTheWireLines() {
        String stackTrace = """
                java.lang.IllegalStateException: first\rsecond
                \tat org.peekaboot.app.OrderController.boom(OrderController.java:42)
                """;
        var root = span("root")
                .named("GET /boom")
                .kind(Span.Kind.SERVER)
                .at(0, 100)
                .exception("java.lang.IllegalStateException", "first\rsecond", stackTrace)
                .build();

        SpanExceptionInfo exception =
                map(foldingMapper, TraceDatas.of("trace1", root)).rootSpan().exception();

        String[] wireLines = exception.stackTrace().split("\n", -1);
        assertThat(wireLines[exception.applicationFrames().getFirst().start()])
                .contains("org.peekaboot.app.OrderController.boom");
    }

    @Test
    void aSpanThatFailedWithoutAnExceptionEventServesOnlyItsMessage() {
        var root = span("root")
                .named("GET /boom")
                .kind(Span.Kind.SERVER)
                .at(0, 100)
                .exception(null, "boom", null)
                .build();

        assertThat(map(foldingMapper, TraceDatas.of("trace1", root)).rootSpan().exception())
                .isEqualTo(new SpanExceptionInfo(null, "boom", null, List.of(), List.of()));
    }

    @Test
    void aSpanThatDidNotFailHasNoException() {
        var root =
                span("root").named("GET /ok").kind(Span.Kind.SERVER).at(0, 100).build();

        assertThat(map(foldingMapper, TraceDatas.of("trace1", root)).rootSpan().exception())
                .isNull();
    }

    @Test
    void theRequestPathIsMaskedLikeTheTagItCameFrom() {
        var root = span("root")
                .named("http get /reset")
                .kind(Span.Kind.SERVER)
                .at(0, 10)
                .tag("http.request.method", "GET")
                .tag("url.path", "/reset;token=abc123")
                .build();

        TraceTree tree = map(mapper, TraceDatas.of("trace1", root));

        assertThat(tree.request().path()).isEqualTo("/reset;token=******");
        assertThat(tree.rootSpan().tags()).containsEntry("url.path", "/reset;token=******");
    }

    @Test
    void mapsSpanEventsFromNonEmptyEventsList() {
        var eventTime = Instant.parse("2024-01-15T10:00:00.500Z");
        var span = span("span1")
                .kind(Span.Kind.SERVER)
                .at(0, 100)
                .events(List.of(new SpanData.Event("cache-miss", eventTime)))
                .build();

        var traceData = TraceDatas.of("trace1", span);

        TraceTree result = map(mapper, traceData);

        assertThat(result.rootSpan().events()).hasSize(1);
        assertThat(result.rootSpan().events().getFirst().name()).isEqualTo("cache-miss");
        assertThat(result.rootSpan().events().getFirst().timestamp()).isEqualTo(eventTime);
    }

    /**
     * The statement a query span carries is served once, masked, as {@code SpanNode.statement};
     * the raw {@code db.query.text}/{@code db.statement}/{@code jdbc.query[N]} tags it was
     * read from are dropped from the span's tags rather than shipped a second time.
     */
    @Test
    void dropsTheStatementTagsTheStatementFieldAlreadyCarries() {
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
                .tags(Map.of(
                        "db.query.text", "select * from orders",
                        "db.statement", "select * from orders",
                        "db.system.name", "h2"))
                .build();
        var proxyQuery = span("q2")
                .parent("root")
                .named("query")
                .kind(Span.Kind.CLIENT)
                .at(40, 20)
                .tags(Map.of("jdbc.query[0]", "select * from lines", "jdbc.datasource.name", "primary"))
                .build();

        TraceTree result = map(mapper, TraceDatas.of("trace1", root, otelQuery, proxyQuery));

        assertThat(result.rootSpan().children())
                .extracting(SpanNode::spanId, SpanNode::tags, TraceTreeMapperSpanContentTest::statementText)
                .containsExactly(
                        tuple("q1", Map.of("db.system.name", "h2"), "select * from orders"),
                        tuple("q2", Map.of("jdbc.datasource.name", "primary"), "select * from lines"));
    }

    /**
     * Only a query span gets a {@code query} field, so dropping the statement tag off any other
     * span would lose the statement from both places.
     */
    @Test
    void keepsTheStatementTagOnASpanThatIsNoQuery() {
        var root = span("root")
                .named("GET /orders")
                .kind(Span.Kind.SERVER)
                .at(0, 200)
                .build();
        var internal = span("s1")
                .parent("root")
                .named("OrderRepository.findAll")
                .at(10, 20)
                .tags(Map.of("db.statement", "select * from orders"))
                .build();

        TraceTree result = map(mapper, TraceDatas.of("trace1", root, internal));

        assertThat(result.rootSpan().children())
                .extracting(SpanNode::tags, TraceTreeMapperSpanContentTest::statementText)
                .containsExactly(tuple(Map.of("db.statement", "select * from orders"), null));
    }

    @Test
    void aQuerySpanCarriesItsStatementLaidOutByTheMappersFormatter() {
        TraceTreeMapper formattingMapper =
                mapperWith(new HibernateSqlFormatter(), new StackTraceRules(List.of(), List.of()));

        SpanNode node =
                map(formattingMapper, personLookupTrace()).rootSpan().children().getFirst();

        assertThat(node.statement().formatted()).isNotNull();
        assertThat(node.statement().parameters()).containsExactly(List.of("42"));
    }

    @Test
    void servesTheParameterTagsInsideTheStatementNotBesideThem() {
        var root = span("root").kind(Span.Kind.SERVER).at(0, 100).build();
        var cappedBatch = jdbcQuery("q1", "insert into person (id) values (?)")
                .parent("root")
                .at(10, 20)
                .tag(QueryParameterTags.PARAMETERS_TAG, "[[\"42\"]]")
                .tag(QueryParameterTags.OMITTED_PARAMETER_SETS_TAG, "3")
                .build();

        SpanNode node = map(mapper, TraceDatas.of("trace1", root, cappedBatch))
                .rootSpan()
                .children()
                .getFirst();

        assertThat(node.tags())
                .doesNotContainKey(QueryParameterTags.PARAMETERS_TAG)
                .doesNotContainKey(QueryParameterTags.OMITTED_PARAMETER_SETS_TAG);
        assertThat(node.statement().parameters()).containsExactly(List.of("42"));
        assertThat(node.statement().omittedParameterSets()).isEqualTo(3);
    }

    /** A summary serves no tag, so its mapping copies and masks none. */
    @Test
    void aSummaryMappingLeavesTagsAsStored() {
        var root = span("root")
                .kind(Span.Kind.SERVER)
                .at(0, 100)
                .tag("http.url", "/reset;token=abc123")
                .build();
        TraceData traceData = TraceDatas.of("trace1", root);

        SpanNode node = mapper.map(traceData, mapper.forSummary()).rootSpan();

        assertThat(node.tags()).isSameAs(traceData.rootSpan().tags());
    }

    /** A summary serves a statement only as a repeated query's text, so it lays none out and reads no parameter. */
    @Test
    void aSummaryMappingCarriesTheMaskedStatementTextAlone() {
        TraceTreeMapper formattingMapper =
                mapperWith(new HibernateSqlFormatter(), new StackTraceRules(List.of(), List.of()));
        var root = span("root").kind(Span.Kind.SERVER).at(0, 100).build();
        var query = jdbcQuery("q1", "SELECT 1 /* ;token=abc123 */")
                .parent("root")
                .at(10, 20)
                .tag(QueryParameterTags.PARAMETERS_TAG, "[[\"42\"]]")
                .tag(QueryParameterTags.OMITTED_PARAMETER_SETS_TAG, "3")
                .build();

        SqlStatement statement = formattingMapper
                .map(TraceDatas.of("trace1", root, query), formattingMapper.forSummary())
                .rootSpan()
                .children()
                .getFirst()
                .statement();

        assertThat(statement.text()).contains("******").doesNotContain("abc123");
        assertThat(statement.formatted()).isNull();
        assertThat(statement.parameters()).isEmpty();
        assertThat(statement.omittedParameterSets()).isZero();
    }

    /** Only a set of issue types leaves this mapping, so it masks no tag and no statement and maps no exception. */
    @Test
    void anIssueMappingCarriesTagsAndStatementsAsStoredAndNoException() {
        var root = span("root")
                .kind(Span.Kind.SERVER)
                .at(0, 100)
                .tag("http.url", "/reset;token=abc123")
                .build();
        var query = jdbcQuery("q1", "SELECT 1 /* ;token=abc123 */")
                .parent("root")
                .at(10, 20)
                .exception("java.sql.SQLException", "refused for ;token=abc123")
                .build();
        TraceData traceData = TraceDatas.of("trace1", root, query);

        SpanNode mappedRoot = mapper.map(traceData, mapper.forIssues()).rootSpan();
        SpanNode mappedQuery = mappedRoot.children().getFirst();

        assertThat(mappedRoot.tags()).isSameAs(traceData.rootSpan().tags());
        assertThat(mappedQuery.statement().text()).isEqualTo("SELECT 1 /* ;token=abc123 */");
        assertThat(mappedQuery.exception()).isNull();
    }

    /**
     * The row count belongs to the query a reader is looking at, not to the {@code result-set}
     * span datasource-proxy records it on. The pairing is {@link RowCounts}', the one the
     * Queries tab reads, so a query reports the same number in both tabs.
     */
    @Test
    void servesTheRowCountOnTheQuerySpanItBelongsTo() {
        var root = span("root")
                .named("GET /orders")
                .kind(Span.Kind.SERVER)
                .at(0, 200)
                .build();
        var ordersQuery = jdbcQuery("q1", "select * from orders")
                .parent("root")
                .at(10, 5)
                .order(1)
                .build();
        var ordersResultSet =
                resultSet("rs", 10).parent("root").at(20, 5).order(2).build();
        var queryWithUnparsableCount = jdbcQuery("q2", "select * from items")
                .parent("root")
                .at(30, 5)
                .order(3)
                .build();
        var unparsable = span("rs2")
                .parent("root")
                .named("result-set")
                .kind(Span.Kind.CLIENT)
                .at(40, 5)
                .tags(Map.of("jdbc.row-count", "not-a-number"))
                .order(4)
                .build();

        TraceData trace =
                TraceDatas.of("trace1", root, ordersQuery, ordersResultSet, queryWithUnparsableCount, unparsable);

        TraceTree result = map(mapper, trace);

        assertThat(result.rootSpan().rowCount()).isNull();
        assertThat(result.rootSpan().children())
                .extracting(SpanNode::spanId, SpanNode::rowCount)
                .containsExactly(tuple("q1", 10L), tuple("rs", null), tuple("q2", null), tuple("rs2", null));
    }

    private static SpanData aRootThatFailedEchoingACredential() {
        String stackTrace = """
                java.lang.IllegalStateException: GET https://api.x/v1?api_key=SECRET failed
                \tat org.peekaboot.app.OrderController.boom(OrderController.java:42)
                \tat org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:1)
                \tat org.springframework.web.filter.OncePerRequestFilter.doFilter(OncePerRequestFilter.java:2)""";
        return span("root")
                .named("GET /boom")
                .kind(Span.Kind.SERVER)
                .at(0, 100)
                .exception("java.lang.IllegalStateException", "GET https://api.x/v1?api_key=SECRET failed", stackTrace)
                .build();
    }

    private static String statementText(SpanNode node) {
        return node.statement() == null ? null : node.statement().text();
    }

    /** A request whose one query bound 42. */
    private static TraceData personLookupTrace() {
        var root = span("root")
                .named("GET /persons/42")
                .kind(Span.Kind.SERVER)
                .at(0, 100)
                .build();
        var lookup = jdbcQuery("q1", "select p.email from person p where p.id=?")
                .parent("root")
                .at(10, 20)
                .tag(QueryParameterTags.PARAMETERS_TAG, "[[\"42\"]]")
                .build();
        return TraceDatas.of("trace1", root, lookup);
    }
}
