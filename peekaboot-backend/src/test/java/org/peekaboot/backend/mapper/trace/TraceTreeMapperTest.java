package org.peekaboot.backend.mapper.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.peekaboot.backend.testsupport.Spans.span;

import io.micrometer.tracing.Span;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.SpanStatus;
import org.peekaboot.backend.domain.trace.TraceStatus;
import org.peekaboot.backend.domain.trace.TraceTree;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.tracing.store.SpanData;
import org.peekaboot.backend.tracing.store.TraceData;

class TraceTreeMapperTest {

    private final TraceTreeMapper mapper = new TraceTreeMapper(new MaskingEngine());

    @Test
    void map_shouldBuildTreeFromFlatSpans() {
        // Given: A trace with root -> child1 -> grandchild, and root -> child2
        var rootSpan = createSpan(
                "trace1", "span-root", null, "root-op", Span.Kind.SERVER, 0, 100, Map.of("service.name", "api"));
        var child1 = createSpan(
                "trace1",
                "span-child1",
                "span-root",
                "child1-op",
                Span.Kind.CLIENT,
                10,
                40,
                Map.of("service.name", "api"));
        var child2 = createSpan(
                "trace1",
                "span-child2",
                "span-root",
                "child2-op",
                Span.Kind.CLIENT,
                50,
                30,
                Map.of("service.name", "api"));
        var grandchild = createSpan(
                "trace1",
                "span-grandchild",
                "span-child1",
                "grandchild-op",
                Span.Kind.CLIENT,
                15,
                20,
                Map.of("service.name", "api"));

        var traceData = TraceData.fromSpans("trace1", List.of(rootSpan, child1, child2, grandchild));

        // When
        TraceTree result = mapper.map(traceData, false);

        // Then
        assertThat(result.traceId()).isEqualTo("trace1");
        assertThat(result.rootSpan()).isNotNull();
        assertThat(result.rootSpan().spanId()).isEqualTo("span-root");
        assertThat(result.rootSpan().name()).isEqualTo("root-op");
        assertThat(result.rootSpan().children()).hasSize(2);

        // Children should be sorted by startTime
        assertThat(result.rootSpan().children().get(0).spanId()).isEqualTo("span-child1");
        assertThat(result.rootSpan().children().get(1).spanId()).isEqualTo("span-child2");

        // Grandchild should be under child1
        SpanNode child1Node = result.rootSpan().children().get(0);
        assertThat(child1Node.children()).hasSize(1);
        assertThat(child1Node.children().get(0).spanId()).isEqualTo("span-grandchild");
    }

    @Test
    void map_shouldAttachOrphanSubtreesToRoot() {
        // Spans can arrive before their parent is exported; a subtree whose
        // parent is missing must not silently vanish from the tree.
        var root = createSpan("trace1", "root", null, "root-op", Span.Kind.SERVER, 0, 100, Map.of());
        var orphan = createSpan("trace1", "orphan", "missing-parent", "orphan-op", Span.Kind.CLIENT, 10, 20, Map.of());
        var orphanChild =
                createSpan("trace1", "orphan-child", "orphan", "orphan-child-op", Span.Kind.CLIENT, 12, 5, Map.of());

        var traceData = TraceData.fromSpans("trace1", List.of(root, orphan, orphanChild));

        TraceTree result = mapper.map(traceData, false);

        assertThat(result.rootSpan().spanId()).isEqualTo("root");
        assertThat(result.rootSpan().children()).extracting(SpanNode::spanId).contains("orphan");
        SpanNode orphanNode = result.rootSpan().children().stream()
                .filter(c -> c.spanId().equals("orphan"))
                .findFirst()
                .orElseThrow();
        assertThat(orphanNode.children()).extracting(SpanNode::spanId).containsExactly("orphan-child");
    }

    @Test
    void map_shouldIdentifyRootSpanWithNullParentId() {
        var rootSpan = createSpan("trace1", "root-id", null, "root-op", Span.Kind.SERVER, 0, 100, Map.of());
        var childSpan = createSpan("trace1", "child-id", "root-id", "child-op", Span.Kind.CLIENT, 10, 50, Map.of());

        var traceData = TraceData.fromSpans("trace1", List.of(childSpan, rootSpan)); // Intentionally reversed

        TraceTree result = mapper.map(traceData, false);

        assertThat(result.rootSpan()).isNotNull();
        assertThat(result.rootSpan().spanId()).isEqualTo("root-id");
        assertThat(result.rootOperation()).isEqualTo("root-op");
    }

    @Test
    void map_shouldKeepTagsOnEachSpan() {
        // Given: Parent span with two children, each with their own tags
        var parent = createSpan(
                "trace1", "parent", null, "parent-op", Span.Kind.SERVER, 0, 100, Map.of("service.name", "api"));
        var child1 = createSpan(
                "trace1",
                "child1",
                "parent",
                "child1-op",
                Span.Kind.CLIENT,
                10,
                30,
                Map.of("db.system", "postgresql", "db.name", "mydb"));
        var child2 = createSpan(
                "trace1",
                "child2",
                "parent",
                "child2-op",
                Span.Kind.CLIENT,
                50,
                30,
                Map.of("db.system", "postgresql", "db.name", "mydb"));

        var traceData = TraceData.fromSpans("trace1", List.of(parent, child1, child2));

        // When
        TraceTree result = mapper.map(traceData, false);

        // Then: every span keeps its own tags
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
    void map_shouldMaskASensitiveShapedTagValue() {
        var root = createSpan(
                "trace1",
                "root",
                null,
                "root-op",
                Span.Kind.SERVER,
                0,
                100,
                Map.of("http.request.header.authorization", "Bearer abc123"));

        var traceData = TraceData.fromSpans("trace1", List.of(root));

        TraceTree result = mapper.map(traceData, false);

        assertThat(result.rootSpan().tags()).containsEntry("http.request.header.authorization", "******");
    }

    @Test
    void map_shouldNotMaskOrdinaryTagsLikeHttpMethod() {
        var root = createSpan(
                "trace1",
                "root",
                null,
                "root-op",
                Span.Kind.SERVER,
                0,
                100,
                Map.of("http.method", "GET", "http.status_code", "200"));

        var traceData = TraceData.fromSpans("trace1", List.of(root));

        TraceTree result = mapper.map(traceData, false);

        assertThat(result.rootSpan().tags()).containsEntry("http.method", "GET");
        assertThat(result.rootSpan().tags()).containsEntry("http.status_code", "200");
    }

    @Test
    void map_shouldApplyValuePatternRulesToATagValueUnderAnInnocuousKey() {
        var root = createSpan(
                "trace1",
                "root",
                null,
                "root-op",
                Span.Kind.SERVER,
                0,
                100,
                Map.of("http.url", "https://admin:hunter2@example.com/api"));

        var traceData = TraceData.fromSpans("trace1", List.of(root));

        TraceTree result = mapper.map(traceData, false);

        assertThat(result.rootSpan().tags().get("http.url")).isEqualTo("https://******@example.com/api");
    }

    @Test
    void map_shouldCalculateTraceSummary() {
        // Given: A trace with DB queries and HTTP calls
        var root = createSpan(
                "trace1", "root", null, "GET /api/users", Span.Kind.SERVER, 0, 200, Map.of("http.method", "GET"));
        var dbQuery1 = createSpan(
                "trace1", "db1", "root", "SELECT users", Span.Kind.CLIENT, 10, 50, Map.of("db.system", "postgresql"));
        var dbQuery2 =
                createSpan("trace1", "db2", "root", "SELECT roles", Span.Kind.CLIENT, 70, 30, Map.of("db.type", "sql"));
        var httpCall = createSpan(
                "trace1",
                "http1",
                "root",
                "GET /external",
                Span.Kind.CLIENT,
                110,
                60,
                Map.of("http.url", "http://external.com"));

        var traceData = TraceData.fromSpans("trace1", List.of(root, dbQuery1, dbQuery2, httpCall));

        // When
        TraceTree result = mapper.map(traceData, false);

        // Then
        assertThat(result.summary().spans().count()).isEqualTo(4);
        assertThat(result.summary().queries().count()).isEqualTo(2);
        assertThat(result.summary().queries().totalDurationMs()).isEqualTo(80L); // 50 + 30
        assertThat(result.summary().spans().errorCount()).isEqualTo(0);
    }

    @Test
    void map_shouldCalculateTraceSummary_withJdbcQueryTags() {
        // Given: A trace with datasource-proxy/Micrometer style jdbc.query tags
        var root = createSpan(
                "trace1", "root", null, "http get /", Span.Kind.SERVER, 0, 100, Map.of("http.method", "GET"));
        var connection = createSpan(
                "trace1",
                "conn",
                "root",
                "connection",
                Span.Kind.CLIENT,
                10,
                50,
                Map.of("jdbc.datasource.name", "mydb"));
        var query = createSpan(
                "trace1",
                "query",
                "root",
                "query",
                Span.Kind.CLIENT,
                20,
                30,
                Map.of("jdbc.query[0]", "SELECT * FROM users"));
        var resultSet = createSpan(
                "trace1", "rs", "root", "result-set", Span.Kind.CLIENT, 55, 5, Map.of("jdbc.row-count", "10"));

        var traceData = TraceData.fromSpans("trace1", List.of(root, connection, query, resultSet));

        // When
        TraceTree result = mapper.map(traceData, false);

        // Then: Only the query span with jdbc.query* tag should be counted, not connection or result-set
        assertThat(result.summary().queries().count()).isEqualTo(1);
        assertThat(result.summary().queries().totalDurationMs()).isEqualTo(30L);
    }

    /**
     * One predicate, {@link DbSpans#isQuery}, decides what a query is for the summary count,
     * for the Queries tab and for the issue detector's per-span children count - so a trace
     * mixing every span shape the JDBC instrumentations emit reports one number, not three.
     */
    @Test
    void map_countsExactlyTheSpansTheQueriesTabListsAndTheIssueDetectorInspects() {
        var root = createSpan("trace1", "root", null, "GET /orders", Span.Kind.SERVER, 0, 200, Map.of());
        var otelQuery = createSpan(
                "trace1",
                "q1",
                "root",
                "SELECT orders",
                Span.Kind.CLIENT,
                10,
                20,
                Map.of("db.query.text", "select * from orders", "db.system.name", "h2"));
        var proxyQuery = createSpan(
                "trace1",
                "q2",
                "root",
                "query",
                Span.Kind.CLIENT,
                40,
                20,
                Map.of("jdbc.query[0]", "select * from lines"));
        var untaggedStatement =
                createSpan("trace1", "q3", "root", "SELECT users", Span.Kind.CLIENT, 70, 20, Map.of("db.system", "h2"));
        var connection = createSpan(
                "trace1",
                "conn",
                "root",
                "connection",
                Span.Kind.CLIENT,
                5,
                100,
                Map.of("jdbc.datasource.name", "primary"));
        var resultSet = createSpan(
                "trace1", "rs", "root", "result-set", Span.Kind.CLIENT, 60, 5, Map.of("jdbc.row-count", "3"));
        var serverWithDbTag = createSpan(
                "trace1", "srv", "root", "handle", Span.Kind.SERVER, 90, 5, Map.of("db.statement", "SELECT 1"));
        var httpCall = createSpan(
                "trace1", "http", "root", "GET /ext", Span.Kind.CLIENT, 100, 50, Map.of("http.url", "http://x"));
        var traceData = TraceData.fromSpans(
                "trace1",
                List.of(
                        root,
                        otelQuery,
                        proxyQuery,
                        untaggedStatement,
                        connection,
                        resultSet,
                        serverWithDbTag,
                        httpCall));

        TraceTree result = mapper.map(traceData, false);
        int queriesListed =
                new QueryExtractor(new MaskingEngine()).extract(traceData).size();
        long queryNodes =
                result.rootSpan().children().stream().filter(DbSpans::isQuery).count();

        assertThat(result.summary().queries().count()).isEqualTo(3);
        assertThat(queriesListed).isEqualTo(result.summary().queries().count());
        assertThat(queryNodes).isEqualTo(result.summary().queries().count());
    }

    @Test
    void map_putsTheMaskedStatementOnQuerySpansOnly() {
        var root = createSpan("trace1", "root", null, "GET /orders", Span.Kind.SERVER, 0, 200, Map.of());
        var query = createSpan(
                "trace1",
                "q1",
                "root",
                "query",
                Span.Kind.CLIENT,
                10,
                20,
                Map.of("db.query.text", "INSERT INTO hooks VALUES ('https://admin:hunter2@example.com/x')"));
        var statementless =
                createSpan("trace1", "q2", "root", "query", Span.Kind.CLIENT, 40, 20, Map.of("db.system", "h2"));
        var connection = createSpan(
                "trace1",
                "conn",
                "root",
                "connection",
                Span.Kind.CLIENT,
                5,
                100,
                Map.of("jdbc.datasource.name", "primary"));

        TraceTree result =
                mapper.map(TraceData.fromSpans("trace1", List.of(root, query, statementless, connection)), false);

        assertThat(result.rootSpan().query()).isNull();
        assertThat(result.rootSpan().children())
                .extracting(SpanNode::spanId, SpanNode::query)
                .containsExactly(
                        tuple("conn", null),
                        tuple("q1", "INSERT INTO hooks VALUES ('https://******@example.com/x')"),
                        tuple("q2", null));
    }

    @Test
    void map_shouldCountErrors() {
        var root = createSpan("trace1", "root", null, "root-op", Span.Kind.SERVER, 0, 100, Map.of());
        var errorSpan = createSpanWithError(
                "trace1",
                "error",
                "root",
                "error-op",
                Span.Kind.CLIENT,
                10,
                50,
                Map.of(),
                "Connection refused",
                "java.net.ConnectException");

        var traceData = TraceData.fromSpans("trace1", List.of(root, errorSpan));

        TraceTree result = mapper.map(traceData, false);

        assertThat(result.summary().spans().errorCount()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(TraceStatus.HAS_ERRORS);
    }

    // errorMessage/errorClass go through the masker like tags do: a realistic exception
    // message can itself carry a credential, e.g. an HTTP client exception that echoes the
    // failing request's URL back with a query-string API key attached.
    @Test
    void map_shouldMaskACredentialEmbeddedInTheSpanErrorMessage() {
        var root = createSpan("trace1", "root", null, "root-op", Span.Kind.SERVER, 0, 100, Map.of());
        var errorSpan = createSpanWithError(
                "trace1",
                "error",
                "root",
                "error-op",
                Span.Kind.CLIENT,
                10,
                50,
                Map.of(),
                "HttpClientErrorException: 401 on GET \"https://api.x/v1?api_key=SECRET\"",
                "org.springframework.web.client.HttpClientErrorException");

        var traceData = TraceData.fromSpans("trace1", List.of(root, errorSpan));

        TraceTree result = mapper.map(traceData, false);

        SpanNode maskedErrorSpan = result.rootSpan().children().getFirst();
        assertThat(maskedErrorSpan.errorMessage()).doesNotContain("SECRET").contains("api_key=******");
    }

    @Test
    void map_shouldReturnOkStatusWhenNoErrors() {
        var root = createSpan("trace1", "root", null, "root-op", Span.Kind.SERVER, 0, 100, Map.of());

        var traceData = TraceData.fromSpans("trace1", List.of(root));

        TraceTree result = mapper.map(traceData, false);

        assertThat(result.status()).isEqualTo(TraceStatus.OK);
    }

    @Test
    void map_shouldHandleEmptyTrace() {
        var traceData = new TraceData("trace1", null, null, null, 0, List.of());

        TraceTree result = mapper.map(traceData, false);

        assertThat(result.traceId()).isEqualTo("trace1");
        assertThat(result.rootSpan()).isNull();
        assertThat(result.summary().spans().count()).isEqualTo(0);
        assertThat(result.status()).isEqualTo(TraceStatus.OK);
        // No root span means nothing to classify from - not a guess at the commonest type.
        assertThat(result.rootActionType()).isEqualTo(RootActionType.UNKNOWN);
    }

    @Test
    void map_shouldHandleSingleSpanTrace() {
        var singleSpan = createSpan(
                "trace1", "only-span", null, "single-op", Span.Kind.SERVER, 0, 100, Map.of("service.name", "api"));

        var traceData = TraceData.fromSpans("trace1", List.of(singleSpan));

        TraceTree result = mapper.map(traceData, false);

        assertThat(result.rootSpan()).isNotNull();
        assertThat(result.rootSpan().spanId()).isEqualTo("only-span");
        assertThat(result.rootSpan().children()).isEmpty();
        assertThat(result.summary().spans().count()).isEqualTo(1);
        assertThat(result.rootOperation()).isEqualTo("single-op");
    }

    @Test
    void map_defaultsTruncatedToFalseWhenOmitted() {
        var singleSpan = createSpan("trace1", "only-span", null, "single-op", Span.Kind.SERVER, 0, 100, Map.of());

        TraceTree result = mapper.map(TraceData.fromSpans("trace1", List.of(singleSpan)), false);

        assertThat(result.truncated()).isFalse();
    }

    @Test
    void map_carriesTheTruncatedFlagGivenByTheCaller() {
        var singleSpan = createSpan("trace1", "only-span", null, "single-op", Span.Kind.SERVER, 0, 100, Map.of());

        TraceTree result = mapper.map(TraceData.fromSpans("trace1", List.of(singleSpan)), true);

        assertThat(result.truncated()).isTrue();
    }

    @Test
    void map_carriesTheTruncatedFlagEvenForAnEmptyTrace() {
        var traceData = new TraceData("trace1", null, null, null, 0, List.of());

        TraceTree result = mapper.map(traceData, true);

        assertThat(result.truncated()).isTrue();
    }

    @Test
    void map_shouldConvertTimesToMilliseconds() {
        var baseTime = Instant.parse("2024-01-15T10:00:00Z");
        var span = span("span1")
                .kind(Span.Kind.SERVER)
                .at(baseTime, Duration.ofMillis(150))
                .build();

        var traceData = TraceData.fromSpans("trace1", List.of(span));

        TraceTree result = mapper.map(traceData, false);

        assertThat(result.startTimeMs()).isEqualTo(baseTime.toEpochMilli());
        assertThat(result.durationMs()).isEqualTo(150L);
        assertThat(result.rootSpan().startTimeMs()).isEqualTo(baseTime.toEpochMilli());
        assertThat(result.rootSpan().durationMs()).isEqualTo(150L);
    }

    @Test
    void map_shouldSetSpanStatusBasedOnError() {
        var okSpan = createSpan("trace1", "ok", null, "ok-op", Span.Kind.SERVER, 0, 100, Map.of());
        var errorChild = createSpanWithError(
                "trace1", "error", "ok", "error-op", Span.Kind.CLIENT, 10, 50, Map.of(), "Error", "Exception");

        var traceData = TraceData.fromSpans("trace1", List.of(okSpan, errorChild));

        TraceTree result = mapper.map(traceData, false);

        assertThat(result.rootSpan().status()).isEqualTo(SpanStatus.OK);
        assertThat(result.rootSpan().children().get(0).status()).isEqualTo(SpanStatus.ERROR);
    }

    @Test
    void map_shouldHandleOrphanSpans() {
        // Given: A span whose parent doesn't exist in the trace (orphan)
        var orphan = createSpan("trace1", "orphan", "missing-parent", "orphan-op", Span.Kind.CLIENT, 0, 50, Map.of());

        var traceData = TraceData.fromSpans("trace1", List.of(orphan));

        // When: The orphan should become a root
        TraceTree result = mapper.map(traceData, false);

        // Then
        assertThat(result.rootSpan()).isNotNull();
        assertThat(result.rootSpan().spanId()).isEqualTo("orphan");
    }

    // --- Root Action Type Detection Tests ---

    /**
     * The full classification grid: every {@link Span.Kind}, plus the null kind Micrometer uses
     * for internal spans, against every tag family {@code detectRootActionType()} inspects.
     * Scheduled-job detection keys on a tag <em>pair</em> rather than a prefix, so it sits
     * outside this grid and is covered by the named tests below.
     *
     * <p>Three rows look like misses and are not. CLIENT or null kind carrying {@code http.}/
     * {@code rpc.} tags is an outbound call that became the root only because its caller's span
     * hasn't arrived, so what started the trace is genuinely unknown. SERVER carrying {@code db.}
     * tags falls to the inbound default. PRODUCER carrying {@code messaging.} tags is a send, not
     * a receive.
     */
    @ParameterizedTest(name = "{0} kind with tags {1} -> {2}")
    @MethodSource("rootActionTypeGrid")
    void map_shouldClassifyRootActionTypeFromKindAndTags(
            Span.Kind kind, Map<String, String> tags, RootActionType expected) {
        var rootSpan = createSpan("trace1", "root", null, "root-op", kind, 0, 100, tags);

        var traceData = TraceData.fromSpans("trace1", List.of(rootSpan));

        TraceTree result = mapper.map(traceData, false);

        assertThat(result.rootActionType()).isEqualTo(expected);
    }

    private static Stream<Arguments> rootActionTypeGrid() {
        Map<String, String> none = Map.of();
        Map<String, String> http = Map.of("http.method", "GET");
        Map<String, String> rpc = Map.of("rpc.system", "grpc");
        Map<String, String> messaging = Map.of("messaging.system", "kafka");
        Map<String, String> db = Map.of("db.system", "postgresql");
        return Stream.of(
                Arguments.of(Span.Kind.SERVER, none, RootActionType.HTTP_REQUEST),
                Arguments.of(Span.Kind.SERVER, http, RootActionType.HTTP_REQUEST),
                Arguments.of(Span.Kind.SERVER, rpc, RootActionType.RPC_CALL),
                Arguments.of(Span.Kind.SERVER, messaging, RootActionType.MESSAGE_CONSUMER),
                Arguments.of(Span.Kind.SERVER, db, RootActionType.HTTP_REQUEST),
                Arguments.of(Span.Kind.CLIENT, none, RootActionType.UNKNOWN),
                Arguments.of(Span.Kind.CLIENT, http, RootActionType.UNKNOWN),
                Arguments.of(Span.Kind.CLIENT, rpc, RootActionType.UNKNOWN),
                Arguments.of(Span.Kind.CLIENT, messaging, RootActionType.MESSAGE_CONSUMER),
                Arguments.of(Span.Kind.CLIENT, db, RootActionType.DATABASE),
                Arguments.of(Span.Kind.PRODUCER, none, RootActionType.UNKNOWN),
                Arguments.of(Span.Kind.PRODUCER, http, RootActionType.UNKNOWN),
                Arguments.of(Span.Kind.PRODUCER, rpc, RootActionType.UNKNOWN),
                Arguments.of(Span.Kind.PRODUCER, messaging, RootActionType.UNKNOWN),
                Arguments.of(Span.Kind.PRODUCER, db, RootActionType.UNKNOWN),
                Arguments.of(Span.Kind.CONSUMER, none, RootActionType.MESSAGE_CONSUMER),
                Arguments.of(Span.Kind.CONSUMER, http, RootActionType.MESSAGE_CONSUMER),
                Arguments.of(Span.Kind.CONSUMER, rpc, RootActionType.MESSAGE_CONSUMER),
                Arguments.of(Span.Kind.CONSUMER, messaging, RootActionType.MESSAGE_CONSUMER),
                Arguments.of(Span.Kind.CONSUMER, db, RootActionType.MESSAGE_CONSUMER),
                Arguments.of(null, none, RootActionType.INTERNAL),
                Arguments.of(null, http, RootActionType.INTERNAL),
                Arguments.of(null, rpc, RootActionType.INTERNAL),
                Arguments.of(null, messaging, RootActionType.MESSAGE_CONSUMER),
                Arguments.of(null, db, RootActionType.INTERNAL));
    }

    @Test
    void map_shouldDetectScheduledJobRootActionTypeFromScheduledTaskTags() {
        // The shape Spring's DefaultScheduledTaskObservationConvention actually produces:
        // no Span.Kind (it isn't a Sender/Receiver-style context) plus the code.function/
        // code.namespace low-cardinality tag pair.
        var rootSpan = createSpan(
                "trace1",
                "root",
                null,
                "task orderReconciler.reconcileOrders",
                null,
                0,
                100,
                Map.of(
                        "code.function", "reconcileOrders",
                        "code.namespace", "org.peekaboot.testingapp.order.OrderReconciler"));

        var traceData = TraceData.fromSpans("trace1", List.of(rootSpan));

        TraceTree result = mapper.map(traceData, false);

        assertThat(result.rootActionType()).isEqualTo(RootActionType.SCHEDULED_JOB);
    }

    @Test
    void map_shouldNotDetectScheduledJobFromNameAloneWithoutTags() {
        // A bean that merely happens to have "job" in its name must not be misclassified;
        // detection is tag-only. No scheduled-task tags -> falls through to the SERVER
        // default (HTTP_REQUEST), same as any other untagged SERVER-kind root span.
        var rootSpan = createSpan("trace1", "root", null, "batch-job-processor", Span.Kind.SERVER, 0, 100, Map.of());

        var traceData = TraceData.fromSpans("trace1", List.of(rootSpan));

        TraceTree result = mapper.map(traceData, false);

        assertThat(result.rootActionType()).isEqualTo(RootActionType.HTTP_REQUEST);
    }

    @Test
    void map_shouldNotDetectScheduledJobFromPartialTagPair() {
        // Both code.function and code.namespace must be present; code.function alone is not
        // enough (it's a generic low-cardinality key other conventions could also set).
        var rootSpan = createSpan(
                "trace1", "root", null, "some-operation", null, 0, 100, Map.of("code.function", "reconcileOrders"));

        var traceData = TraceData.fromSpans("trace1", List.of(rootSpan));

        TraceTree result = mapper.map(traceData, false);

        assertThat(result.rootActionType()).isEqualTo(RootActionType.INTERNAL);
    }

    @Test
    void map_shouldNotDetectScheduledJobFromCodeTagsOnAServerKindRoot() {
        // Scheduled-task detection is deliberately confined to the kind-less path, because
        // that is the only shape Spring's convention produces. code.function/code.namespace
        // are ordinary OTel source-code attributes any instrumentation may set, so on a
        // SERVER-kind span they say nothing about scheduling and must not outrank the
        // server default.
        var rootSpan = createSpan(
                "trace1",
                "root",
                null,
                "handle-request",
                Span.Kind.SERVER,
                0,
                100,
                Map.of(
                        "code.function", "handleRequest",
                        "code.namespace", "org.peekaboot.testingapp.web.OrderController"));

        var traceData = TraceData.fromSpans("trace1", List.of(rootSpan));

        TraceTree result = mapper.map(traceData, false);

        assertThat(result.rootActionType()).isEqualTo(RootActionType.HTTP_REQUEST);
    }

    @Test
    void map_shouldExtractRequestSummaryFromStandardTags() {
        var root = createSpan(
                "trace1",
                "root",
                null,
                "GET /api/users",
                Span.Kind.SERVER,
                0,
                100,
                Map.of("http.method", "GET", "http.target", "/api/users", "http.status_code", "200"));

        var traceData = TraceData.fromSpans("trace1", List.of(root));

        TraceTree result = mapper.map(traceData, false);

        assertThat(result.summary().request()).isNotNull();
        assertThat(result.summary().request().method()).isEqualTo("GET");
        assertThat(result.summary().request().path()).isEqualTo("/api/users");
        assertThat(result.summary().request().statusCode()).isEqualTo(200);
    }

    @Test
    void map_shouldExtractRequestSummaryFromFallbackTags() {
        var root = createSpan(
                "trace1",
                "root",
                null,
                "POST /api/orders",
                Span.Kind.SERVER,
                0,
                100,
                Map.of("http.request.method", "POST", "url.path", "/api/orders", "http.response.status_code", "201"));

        var traceData = TraceData.fromSpans("trace1", List.of(root));

        TraceTree result = mapper.map(traceData, false);

        assertThat(result.summary().request().method()).isEqualTo("POST");
        assertThat(result.summary().request().path()).isEqualTo("/api/orders");
        assertThat(result.summary().request().statusCode()).isEqualTo(201);
    }

    /**
     * The names Spring Boot's own server-request observation puts on the root span - the
     * only ones a default Boot application ever produces. {@code uri} is the route pattern;
     * {@code http.url} carries the request URI and is the path worth showing.
     */
    @Test
    void map_shouldExtractRequestSummaryFromSpringsDefaultObservationTags() {
        var root = createSpan(
                "trace1",
                "root",
                null,
                "http get /api/users/{id}",
                Span.Kind.SERVER,
                0,
                100,
                Map.of(
                        "method", "GET",
                        "uri", "/api/users/{id}",
                        "status", "200",
                        "outcome", "SUCCESS",
                        "exception", "none",
                        "http.url", "/api/users/42"));

        var traceData = TraceData.fromSpans("trace1", List.of(root));

        TraceTree result = mapper.map(traceData, false);

        assertThat(result.summary().request().method()).isEqualTo("GET");
        assertThat(result.summary().request().path()).isEqualTo("/api/users/42");
        assertThat(result.summary().request().statusCode()).isEqualTo(200);
        assertThat(result.rootActionType()).isEqualTo(RootActionType.HTTP_REQUEST);
    }

    @Test
    void map_shouldTolerateMalformedStatusCodeInRequestSummary() {
        var root = createSpan(
                "trace1",
                "root",
                null,
                "GET /api/users",
                Span.Kind.SERVER,
                0,
                100,
                Map.of("http.method", "GET", "http.status_code", "not-a-number"));

        var traceData = TraceData.fromSpans("trace1", List.of(root));

        TraceTree result = mapper.map(traceData, false);

        assertThat(result.summary().request().method()).isEqualTo("GET");
        assertThat(result.summary().request().statusCode()).isNull();
    }

    @Test
    void map_shouldMapSpanEventsFromNonEmptyEventsList() {
        var eventTime = Instant.parse("2024-01-15T10:00:00.500Z");
        var span = span("span1")
                .kind(Span.Kind.SERVER)
                .at(0, 100)
                .events(List.of(new SpanData.Event("cache-miss", eventTime)))
                .build();

        var traceData = TraceData.fromSpans("trace1", List.of(span));

        TraceTree result = mapper.map(traceData, false);

        assertThat(result.rootSpan().events()).hasSize(1);
        assertThat(result.rootSpan().events().getFirst().name()).isEqualTo("cache-miss");
        assertThat(result.rootSpan().events().getFirst().timestamp()).isEqualTo(eventTime);
    }

    /**
     * The exact shape datasource-micrometer exports when a pooled connection is acquired
     * outside any traced work (an external health probe, HikariCP maintenance): contextual
     * name "connection", CLIENT kind, and only the {@code jdbc.datasource.*} connection
     * keys as tags - no {@code db.*}, no {@code jdbc.query[N]}.
     */
    @Test
    void map_shouldClassifyAStandaloneConnectionSpanAsConnectionPool() {
        var rootSpan = createSpan(
                "trace1",
                "root",
                null,
                "connection",
                Span.Kind.CLIENT,
                0,
                30,
                Map.of(
                        "jdbc.datasource.name", "dataSource",
                        "jdbc.datasource.pool", "HikariPool-1",
                        "jdbc.datasource.driver", "org.h2.Driver"));

        TraceTree result = mapper.map(TraceData.fromSpans("trace1", List.of(rootSpan)), false);

        assertThat(result.rootActionType()).isEqualTo(RootActionType.CONNECTION_POOL);
    }

    @Test
    void map_shouldKeepAQueryRootCarryingPoolTagsAsDatabase() {
        // In a HikariCP app every datasource observation gets jdbc.datasource.* added
        // (HikariJdbcObservationFilter tags query contexts too) - db.* still marks this
        // root as real database work, not pool maintenance.
        var rootSpan = createSpan(
                "trace1",
                "root",
                null,
                "query",
                Span.Kind.CLIENT,
                0,
                30,
                Map.of(
                        "db.query.text", "SELECT 1",
                        "jdbc.datasource.name", "dataSource",
                        "jdbc.datasource.pool", "HikariPool-1"));

        TraceTree result = mapper.map(TraceData.fromSpans("trace1", List.of(rootSpan)), false);

        assertThat(result.rootActionType()).isEqualTo(RootActionType.DATABASE);
    }

    @Test
    void map_shouldNotClassifyAConnectionNamedSpanWithoutDatasourceTagsAsConnectionPool() {
        // The name only counts together with the jdbc.datasource.* tags the
        // datasource-micrometer convention sets - a bare span that happens to share the
        // name says nothing about what started the trace.
        var rootSpan = createSpan("trace1", "root", null, "connection", Span.Kind.CLIENT, 0, 30, Map.of());

        TraceTree result = mapper.map(TraceData.fromSpans("trace1", List.of(rootSpan)), false);

        assertThat(result.rootActionType()).isEqualTo(RootActionType.UNKNOWN);
    }

    private SpanData createSpan(
            String traceId,
            String spanId,
            String parentId,
            String name,
            Span.Kind kind,
            long startOffsetMs,
            long durationMs,
            Map<String, String> tags) {
        return span(spanId)
                .in(traceId)
                .parent(parentId)
                .named(name)
                .kind(kind)
                .at(startOffsetMs, durationMs)
                .tags(tags)
                .build();
    }

    private SpanData createSpanWithError(
            String traceId,
            String spanId,
            String parentId,
            String name,
            Span.Kind kind,
            long startOffsetMs,
            long durationMs,
            Map<String, String> tags,
            String errorMessage,
            String errorClass) {
        return span(spanId)
                .in(traceId)
                .parent(parentId)
                .named(name)
                .kind(kind)
                .at(startOffsetMs, durationMs)
                .tags(tags)
                .error(errorMessage, errorClass)
                .build();
    }
}
