package org.peekaboot.backend.mapper.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.SpanNodes.node;
import static org.peekaboot.backend.testsupport.TraceTrees.tree;

import io.micrometer.tracing.Span;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.peekaboot.backend.domain.trace.IssueSeverity;
import org.peekaboot.backend.domain.trace.IssueType;
import org.peekaboot.backend.domain.trace.IssueUnit;
import org.peekaboot.backend.domain.trace.SpanIssue;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.SpanStatus;
import org.peekaboot.backend.domain.trace.SqlStatement;
import org.peekaboot.backend.domain.trace.TraceIssue;
import org.peekaboot.backend.domain.trace.TraceTree;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;

class IssueDetectorTest {

    private PeekabootTracingProperties properties;
    private IssueDetector detector;

    @BeforeEach
    void setUp() {
        properties = new PeekabootTracingProperties();
        detector = new IssueDetector(properties);
    }

    /** One span past exactly one rule: the issue carries that rule's type, severity and wording. */
    @ParameterizedTest
    @MethodSource("spansPastOneThreshold")
    void raisesTheOneIssueASpanTrips(SpanNode span, SpanIssue expected) {
        TraceTree result = detector.detectIssues(tree(span).build());

        assertThat(result.rootSpan().issues()).containsExactly(expected);
    }

    /** The spans of a trace still running are judged like any others. */
    @Test
    void judgesTheSubtreesOfAnInFlightTrace() {
        SpanNode slowOrphan =
                node("orphan").durationMs(properties.getSlowSpanThresholdMs()).build();
        TraceTree inFlight =
                tree(null).complete(false).orphans(List.of(slowOrphan)).build();

        TraceTree judged = detector.detectIssues(inFlight);

        assertThat(judged.orphans().getFirst().issues())
                .extracting(TraceIssue::type)
                .containsExactly(IssueType.SLOW);
    }

    /** A failed span is shown by its status and error fields; an issue would only repeat them. */
    @Test
    void aFailedSpanRaisesNoIssue() {
        SpanNode span = node("span1")
                .durationMs(50)
                .status(SpanStatus.ERROR)
                .exception("java.net.ConnectException", "Connection refused: db:5432")
                .build();

        TraceTree result = detector.detectIssues(tree(span).build());

        assertThat(result.rootSpan().issues()).isEmpty();
    }

    @Test
    void doesNotFlagResultSetSpansAsSlowQuery() {
        // datasource-proxy connection/result-set spans carry jdbc.* tags but
        // are not queries (same distinction as the trace summary)
        SpanNode span = querySpan("span1", 80, Map.of("jdbc.row-count", "10"));
        TraceTree trace = tree(span).build();

        TraceTree result = detector.detectIssues(trace);

        assertThat(result.rootSpan().issues()).noneMatch(issue -> issue.type() == IssueType.SLOW_QUERY);
    }

    @Test
    void doesNotDetectSlowQueryOnTheServerSideOfADbTaggedExchange() {
        // DbSpans.isQuery: only the CLIENT side of a database call is a query
        SpanNode span = node("span1")
                .durationMs(80)
                .tags(Map.of("db.statement", "SELECT 1"))
                .build();
        TraceTree trace = tree(span).build();

        TraceTree result = detector.detectIssues(trace);

        assertThat(result.rootSpan().issues()).isEmpty();
    }

    @Test
    void doesNotDetectSlowQueryOnNonDbSpan() {
        // 80ms would be a slow query, but this is not a DB span
        SpanNode span =
                node("span1").durationMs(80).tags(Map.of("http.method", "GET")).build();
        TraceTree trace = tree(span).build();

        TraceTree result = detector.detectIssues(trace);

        // and no SLOW either: 80ms is under the 100ms span threshold
        assertThat(result.rootSpan().issues()).isEmpty();
    }

    /**
     * One tree that trips every rule at the defaults: a root with 600ms of its own time and
     * six 80ms direct queries. Raising all three thresholds above it must silence each rule,
     * so a threshold the detector stopped reading would show up here.
     */
    @Test
    void everyThresholdIsRead() {
        List<SpanNode> queries = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            queries.add(querySpan("q" + i, 80, Map.of("db.system", "postgresql")));
        }
        SpanNode root = node("root")
                .durationMs(1080)
                .selfMs(600)
                .children(List.copyOf(queries))
                .build();
        TraceTree trace = tree(root).build();

        TraceTree atDefaults = detector.detectIssues(trace);
        assertThat(atDefaults.rootSpan().issues()).extracting(TraceIssue::type).containsExactly(IssueType.VERY_SLOW);
        assertThat(atDefaults.rootSpan().children())
                .allSatisfy(query ->
                        assertThat(query.issues()).extracting(TraceIssue::type).containsExactly(IssueType.SLOW_QUERY));

        properties.setSlowSpanThresholdMs(700);
        properties.setVerySlowSpanThresholdMs(1000);
        properties.setSlowQueryThresholdMs(100);

        TraceTree raised = detector.detectIssues(trace);
        assertThat(raised.rootSpan().issues()).isEmpty();
        assertThat(raised.rootSpan().children())
                .allSatisfy(query -> assertThat(query.issues()).isEmpty());
    }

    @Test
    void raisesNothingUnderEveryThreshold() {
        SpanNode span = node("span1").durationMs(50).build();
        TraceTree trace = tree(span).build();

        TraceTree result = detector.detectIssues(trace);

        assertThat(result.rootSpan().issues()).isEmpty();
    }

    @Test
    void processesNestedSpansRecursively() {
        SpanNode grandchild = node("gc").durationMs(200).build();
        SpanNode child =
                node("child").durationMs(300).children(List.of(grandchild)).build();
        SpanNode root = node("root").durationMs(50).children(List.of(child)).build();
        TraceTree trace = tree(root).build();

        TraceTree result = detector.detectIssues(trace);

        assertThat(result.rootSpan().issues()).isEmpty();
        assertThat(result.rootSpan().children().get(0).issues()).hasSize(1);
        assertThat(result.rootSpan().children().get(0).issues().get(0).type()).isEqualTo(IssueType.SLOW);
        assertThat(result.rootSpan().children().get(0).children().get(0).issues())
                .hasSize(1);
        assertThat(result.rootSpan()
                        .children()
                        .get(0)
                        .children()
                        .get(0)
                        .issues()
                        .get(0)
                        .type())
                .isEqualTo(IssueType.SLOW);
    }

    /** A slow child is the child's issue; its ancestors only waited for it. */
    @Test
    void aSlowChildDoesNotMarkItsParent() {
        long slowMs = properties.getSlowSpanThresholdMs();
        SpanNode child = node("child").durationMs(slowMs).build();
        SpanNode parent = node("parent")
                .durationMs(slowMs + 5)
                .selfMs(5)
                .children(List.of(child))
                .build();

        TraceTree judged = detector.detectIssues(tree(parent).build());

        assertThat(judged.rootSpan().issues()).isEmpty();
        assertThat(judged.rootSpan().children().getFirst().issues())
                .extracting(TraceIssue::type)
                .containsExactly(IssueType.SLOW);
    }

    /** A query's own wait is the database's, so SLOW_QUERY keeps judging the whole query. */
    @Test
    void slowQueryJudgesTheWholeQuery() {
        long slowQueryMs = properties.getSlowQueryThresholdMs();
        SpanNode query = node("q")
                .kind(Span.Kind.CLIENT)
                .tags(Map.of("db.system", "h2"))
                .durationMs(slowQueryMs)
                .selfMs(0)
                .build();

        TraceTree judged = detector.detectIssues(tree(query).build());

        assertThat(judged.rootSpan().issues()).extracting(TraceIssue::type).containsExactly(IssueType.SLOW_QUERY);
    }

    /** The issue sits on the span that ran the queries, beside the time issues the queries raise themselves. */
    @Test
    void raisesRepeatedQueryOnTheParent() {
        properties.setRepeatedQueryThreshold(2);
        SqlStatement statement = new SqlStatement("select * from t where id = 1", null, List.of(), 0);
        SpanNode parent = node("parent")
                .children(List.of(
                        node("q1").kind(Span.Kind.CLIENT).statement(statement).build(),
                        node("q2").kind(Span.Kind.CLIENT).statement(statement).build()))
                .build();

        TraceTree judged = detector.detectIssues(tree(parent).build());

        assertThat(judged.rootSpan().issues()).extracting(TraceIssue::type).containsExactly(IssueType.REPEATED_QUERY);
    }

    /** The caller's mistake is worth a warning on the request, never a failure of the trace. */
    @Test
    void aServerRootAnswering4xxGetsAClientError() {
        SpanNode root = node("root")
                .kind(Span.Kind.SERVER)
                .tags(Map.of("http.response.status_code", "404"))
                .build();

        TraceTree judged = detector.detectIssues(tree(root).build());

        assertThat(judged.rootSpan().issues())
                .containsExactly(new SpanIssue(
                        IssueType.CLIENT_ERROR,
                        IssueSeverity.WARNING,
                        "root",
                        404,
                        400,
                        IssueUnit.STATUS,
                        "The request was answered with status 404."));
    }

    @ParameterizedTest
    @CsvSource({"SERVER, 200", "SERVER, 500", "CLIENT, 404"})
    void noClientErrorForOtherAnswers(Span.Kind kind, String status) {
        SpanNode root = node("root")
                .kind(kind)
                .tags(Map.of("http.response.status_code", status))
                .build();

        assertThat(detector.detectIssues(tree(root).build()).rootSpan().issues())
                .isEmpty();
    }

    /** A server span below the root answered a call this application made to itself; the outer request reports the outcome. */
    @Test
    void onlyTheRootCarriesTheClientError() {
        SpanNode inner = node("inner")
                .kind(Span.Kind.SERVER)
                .tags(Map.of("status", "404"))
                .build();
        SpanNode root = node("root")
                .kind(Span.Kind.SERVER)
                .tags(Map.of("status", "200"))
                .children(List.of(inner))
                .build();

        TraceTree judged = detector.detectIssues(tree(root).build());

        assertThat(judged.rootSpan().children().getFirst().issues()).isEmpty();
    }

    @Test
    void detectsMultipleIssuesOnSameSpan() {
        SpanNode span = node("span1")
                .kind(Span.Kind.CLIENT)
                .durationMs(200)
                .tags(Map.of("db.system", "postgresql"))
                .build();
        TraceTree trace = tree(span).build();

        TraceTree result = detector.detectIssues(trace);

        List<TraceIssue> issues = result.rootSpan().issues();
        assertThat(issues).extracting(TraceIssue::type).containsExactlyInAnyOrder(IssueType.SLOW, IssueType.SLOW_QUERY);
    }

    @Test
    void leavesATreeWithoutARootSpanAlone() {
        TraceTree trace = tree(null).build();

        TraceTree result = detector.detectIssues(trace);

        assertThat(result.rootSpan()).isNull();
    }

    /** A CLIENT span, the only kind {@code DbSpans.isQuery} accepts. */
    private SpanNode querySpan(String spanId, long durationMs, Map<String, String> tags) {
        return node(spanId)
                .kind(Span.Kind.CLIENT)
                .durationMs(durationMs)
                .tags(tags)
                .build();
    }

    static Stream<Arguments> spansPastOneThreshold() {
        return Stream.of(
                Arguments.of(
                        Named.of(
                                "a span at the slow threshold",
                                node("s").durationMs(100).build()),
                        new SpanIssue(
                                IssueType.SLOW,
                                IssueSeverity.WARNING,
                                "s",
                                100,
                                100,
                                IssueUnit.MS,
                                "This span spent 100 ms in its own work, at or above the 100 ms slow-span threshold.")),
                Arguments.of(
                        Named.of(
                                "a span at the very-slow threshold",
                                node("v").durationMs(500).build()),
                        new SpanIssue(
                                IssueType.VERY_SLOW,
                                IssueSeverity.ERROR,
                                "v",
                                500,
                                500,
                                IssueUnit.MS,
                                "This span spent 500 ms in its own work, at or above the 500 ms very-slow-span threshold.")),
                Arguments.of(
                        Named.of(
                                "a query at the slow-query threshold",
                                node("q")
                                        .kind(Span.Kind.CLIENT)
                                        .tags(Map.of("db.system", "h2"))
                                        .durationMs(50)
                                        .build()),
                        new SpanIssue(
                                IssueType.SLOW_QUERY,
                                IssueSeverity.WARNING,
                                "q",
                                50,
                                50,
                                IssueUnit.MS,
                                "This query took 50 ms, at or above the 50 ms slow-query threshold.")),
                Arguments.of(
                        Named.of(
                                "150ms, between the slow and very-slow thresholds",
                                node("span1").durationMs(150).build()),
                        new SpanIssue(
                                IssueType.SLOW,
                                IssueSeverity.WARNING,
                                "span1",
                                150,
                                100,
                                IssueUnit.MS,
                                "This span spent 150 ms in its own work, at or above the 100 ms slow-span threshold.")),
                Arguments.of(
                        Named.of(
                                "600ms, past the very-slow threshold",
                                node("span1").durationMs(600).build()),
                        new SpanIssue(
                                IssueType.VERY_SLOW,
                                IssueSeverity.ERROR,
                                "span1",
                                600,
                                500,
                                IssueUnit.MS,
                                "This span spent 600 ms in its own work, at or above the 500 ms very-slow-span threshold.")),
                Arguments.of(
                        Named.of(
                                "an 80ms query, past the slow-query threshold",
                                node("span1")
                                        .kind(Span.Kind.CLIENT)
                                        .durationMs(80)
                                        .tags(Map.of("db.system", "postgresql", "db.statement", "SELECT * FROM users"))
                                        .build()),
                        new SpanIssue(
                                IssueType.SLOW_QUERY,
                                IssueSeverity.WARNING,
                                "span1",
                                80,
                                50,
                                IssueUnit.MS,
                                "This query took 80 ms, at or above the 50 ms slow-query threshold.")),
                Arguments.of(
                        Named.of(
                                "a parent with 150ms of its own work in 1080ms",
                                node("p").durationMs(1080).selfMs(150).build()),
                        new SpanIssue(
                                IssueType.SLOW,
                                IssueSeverity.WARNING,
                                "p",
                                150,
                                100,
                                IssueUnit.MS,
                                "This span spent 150 ms in its own work, at or above the 100 ms slow-span threshold.")),
                Arguments.of(
                        Named.of(
                                "a parent with 600ms of its own work in 1080ms",
                                node("p").durationMs(1080).selfMs(600).build()),
                        new SpanIssue(
                                IssueType.VERY_SLOW,
                                IssueSeverity.ERROR,
                                "p",
                                600,
                                500,
                                IssueUnit.MS,
                                "This span spent 600 ms in its own work, at or above the 500 ms very-slow-span threshold.")),
                Arguments.of(
                        Named.of(
                                "an 80ms query with no time of its own",
                                node("q")
                                        .kind(Span.Kind.CLIENT)
                                        .tags(Map.of("db.system", "h2"))
                                        .durationMs(80)
                                        .selfMs(0)
                                        .build()),
                        new SpanIssue(
                                IssueType.SLOW_QUERY,
                                IssueSeverity.WARNING,
                                "q",
                                80,
                                50,
                                IssueUnit.MS,
                                "This query took 80 ms, at or above the 50 ms slow-query threshold.")));
    }
}
