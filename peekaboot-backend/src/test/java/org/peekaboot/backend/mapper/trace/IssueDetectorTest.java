package org.peekaboot.backend.mapper.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
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
import org.junit.jupiter.params.provider.MethodSource;
import org.peekaboot.backend.config.UiTracingProperties;
import org.peekaboot.backend.domain.trace.IssueSeverity;
import org.peekaboot.backend.domain.trace.IssueType;
import org.peekaboot.backend.domain.trace.SpanIssue;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.SpanStatus;
import org.peekaboot.backend.domain.trace.TraceTree;

class IssueDetectorTest {

    private UiTracingProperties properties;
    private IssueDetector detector;

    @BeforeEach
    void setUp() {
        properties = new UiTracingProperties();
        detector = new IssueDetector(properties);
    }

    /** One span past exactly one rule: the issue carries that rule's type, severity and wording. */
    @ParameterizedTest
    @MethodSource("spansPastOneThreshold")
    void raisesTheOneIssueASpanTrips(SpanNode span, SpanIssue expected) {
        TraceTree result = detector.detectIssues(tree(span).build());

        assertThat(result.rootSpan().issues()).containsExactly(expected);
    }

    static Stream<Arguments> spansPastOneThreshold() {
        return Stream.of(
                Arguments.of(
                        Named.of(
                                "150ms, between the slow and very-slow thresholds",
                                node("span1").durationMs(150).build()),
                        new SpanIssue(IssueType.SLOW, "Span took 150ms (threshold: 100ms)", IssueSeverity.WARNING)),
                Arguments.of(
                        Named.of(
                                "600ms, past the very-slow threshold",
                                node("span1").durationMs(600).build()),
                        new SpanIssue(IssueType.VERY_SLOW, "Span took 600ms (threshold: 500ms)", IssueSeverity.ERROR)),
                Arguments.of(
                        Named.of(
                                "an error without a message",
                                node("span1")
                                        .durationMs(50)
                                        .status(SpanStatus.ERROR)
                                        .build()),
                        new SpanIssue(IssueType.ERROR, "Span ended with error", IssueSeverity.ERROR)),
                Arguments.of(
                        Named.of(
                                "an 80ms query, past the slow-query threshold",
                                node("span1")
                                        .kind(Span.Kind.CLIENT)
                                        .durationMs(80)
                                        .tags(Map.of("db.system", "postgresql", "db.statement", "SELECT * FROM users"))
                                        .build()),
                        new SpanIssue(
                                IssueType.SLOW_QUERY, "Query took 80ms (threshold: 50ms)", IssueSeverity.WARNING)));
    }

    /** The thresholds are inclusive: a span exactly on one trips it. */
    @Test
    void aSpanExactlyOnAThresholdTripsIt() {
        TraceTree slow =
                detector.detectIssues(tree(node("s").durationMs(100).build()).build());
        TraceTree verySlow =
                detector.detectIssues(tree(node("v").durationMs(500).build()).build());
        // 50ms is under the 100ms span threshold, so the query threshold is the only one read
        TraceTree slowQuery = detector.detectIssues(
                tree(querySpan("q", 50, Map.of("db.system", "postgresql"))).build());

        assertThat(slow.rootSpan().issues()).extracting(SpanIssue::type).containsExactly(IssueType.SLOW);
        assertThat(verySlow.rootSpan().issues()).extracting(SpanIssue::type).containsExactly(IssueType.VERY_SLOW);
        assertThat(slowQuery.rootSpan().issues()).extracting(SpanIssue::type).containsExactly(IssueType.SLOW_QUERY);
    }

    @Test
    void aBlankErrorMessageFallsThroughToTheTag() {
        SpanNode span = node("span1")
                .status(SpanStatus.ERROR)
                .error("   ", "java.net.ConnectException")
                .tags(Map.of("error.message", "from a tag"))
                .build();

        TraceTree result = detector.detectIssues(tree(span).build());

        assertThat(result.rootSpan().issues()).extracting(SpanIssue::message).containsExactly("from a tag");
    }

    /** A tree without a summary has no trace-level query count to judge; the span rules still run. */
    @Test
    void aTreeWithoutASummaryStillGetsItsSpanIssues() {
        TraceTree trace =
                tree(node("span1").durationMs(150).build()).summary(null).build();

        TraceTree result = detector.detectIssues(trace);

        assertThat(result.rootSpan().issues()).extracting(SpanIssue::type).containsExactly(IssueType.SLOW);
    }

    @Test
    void usesErrorMessageFromSpanAttributeIfAvailable() {
        SpanNode span = node("span1")
                .durationMs(50)
                .status(SpanStatus.ERROR)
                .tags(Map.of("error.message", "Connection refused"))
                .build();
        TraceTree trace = tree(span).build();

        TraceTree result = detector.detectIssues(trace);

        SpanIssue issue = result.rootSpan().issues().get(0);
        assertThat(issue.type()).isEqualTo(IssueType.ERROR);
        assertThat(issue.message()).isEqualTo("Connection refused");
    }

    @Test
    void prefersErrorMessageFieldOverTag() {
        // The exporter stores the error in SpanNode.errorMessage and never writes an
        // error.message tag; another instrumentation may, and the field still wins.
        SpanNode span = node("span1")
                .durationMs(50)
                .status(SpanStatus.ERROR)
                .tags(Map.of("error.message", "from a tag"))
                .error("Connection refused: db:5432", "java.net.ConnectException")
                .build();
        TraceTree trace = tree(span).build();

        TraceTree result = detector.detectIssues(trace);

        SpanIssue issue = result.rootSpan().issues().get(0);
        assertThat(issue.type()).isEqualTo(IssueType.ERROR);
        assertThat(issue.message()).isEqualTo("Connection refused: db:5432");
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

    @Test
    void detectsHighQueryCountOnRootSpan() {
        // 25 queries: over the 20-query trace threshold
        SpanNode child = querySpan("child1", 30, Map.of("db.system", "mysql"));
        SpanNode root = node("root").durationMs(50).children(List.of(child)).build();
        TraceTree trace = tree(root).queries(25, 500L).build();

        TraceTree result = detector.detectIssues(trace);

        assertThat(result.rootSpan().issues()).hasSize(1);
        SpanIssue issue = result.rootSpan().issues().get(0);
        assertThat(issue.type()).isEqualTo(IssueType.HIGH_QUERY_COUNT);
        assertThat(issue.severity()).isEqualTo(IssueSeverity.WARNING);
        assertThat(issue.message()).isEqualTo("Trace has 25 database queries (threshold: 20)");
    }

    @Test
    void doesNotAddHighQueryCountToChildSpans() {
        SpanNode child = querySpan("child1", 30, Map.of("db.system", "mysql"));
        SpanNode root = node("root").durationMs(50).children(List.of(child)).build();
        TraceTree trace = tree(root).queries(25, 500L).build();

        TraceTree result = detector.detectIssues(trace);

        assertThat(result.rootSpan().children().get(0).issues()).isEmpty();
    }

    @Test
    void flagsSpanWithManyDirectQueryChildren() {
        // Default highQueryCountThreshold is 5; six direct query children exceed it
        List<SpanNode> queries = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            queries.add(querySpan("q" + i, 10, Map.of("jdbc.query[0]", "SELECT " + i)));
        }
        SpanNode service =
                node("service").durationMs(80).children(List.copyOf(queries)).build();
        SpanNode root = node("root").durationMs(90).children(List.of(service)).build();
        TraceTree trace = tree(root).queries(6, 60L).build();

        TraceTree result = detector.detectIssues(trace);

        SpanNode serviceNode = result.rootSpan().children().get(0);
        assertThat(serviceNode.issues())
                .extracting(SpanIssue::type, SpanIssue::message, SpanIssue::severity)
                .containsExactly(tuple(
                        IssueType.HIGH_QUERY_COUNT,
                        "Span has 6 direct database queries (threshold: 5)",
                        IssueSeverity.WARNING));
    }

    @Test
    void doesNotFlagSpanWithQueryChildrenAtThreshold() {
        List<SpanNode> queries = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            queries.add(querySpan("q" + i, 10, Map.of("jdbc.query[0]", "SELECT " + i)));
        }
        SpanNode service =
                node("service").durationMs(80).children(List.copyOf(queries)).build();
        SpanNode root = node("root").durationMs(90).children(List.of(service)).build();
        TraceTree trace = tree(root).queries(5, 50L).build();

        TraceTree result = detector.detectIssues(trace);

        SpanNode serviceNode = result.rootSpan().children().get(0);
        assertThat(serviceNode.issues()).noneMatch(issue -> issue.type() == IssueType.HIGH_QUERY_COUNT);
    }

    /**
     * One tree that trips every rule at the defaults: a 600ms root with six 80ms direct
     * queries on a 25-query trace. Raising all five thresholds above it must silence each
     * rule, so a threshold the detector stopped reading would show up here.
     */
    @Test
    void everyThresholdIsRead() {
        List<SpanNode> queries = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            queries.add(querySpan("q" + i, 80, Map.of("db.system", "postgresql")));
        }
        SpanNode root =
                node("root").durationMs(600).children(List.copyOf(queries)).build();
        TraceTree trace = tree(root).queries(25, 480L).build();

        TraceTree atDefaults = detector.detectIssues(trace);
        assertThat(atDefaults.rootSpan().issues())
                .extracting(SpanIssue::type)
                .containsExactlyInAnyOrder(IssueType.VERY_SLOW, IssueType.HIGH_QUERY_COUNT, IssueType.HIGH_QUERY_COUNT);
        assertThat(atDefaults.rootSpan().children())
                .allSatisfy(query ->
                        assertThat(query.issues()).extracting(SpanIssue::type).containsExactly(IssueType.SLOW_QUERY));

        properties.setSlowSpanThresholdMs(700);
        properties.setVerySlowSpanThresholdMs(1000);
        properties.setSlowQueryThresholdMs(100);
        properties.setHighQueryCountThreshold(10);
        properties.setHighTraceQueryCountThreshold(30);

        TraceTree raised = detector.detectIssues(trace);
        assertThat(raised.rootSpan().issues()).isEmpty();
        assertThat(raised.rootSpan().children())
                .allSatisfy(query -> assertThat(query.issues()).isEmpty());
        assertThat(raised.slow()).isFalse();
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

    @Test
    void marksTheTraceSlowWhenAnySpanIsSlowOrVerySlow() {
        SpanNode slowChild = node("child").durationMs(150).build();
        SpanNode root = node("root").durationMs(50).children(List.of(slowChild)).build();

        assertThat(detector.detectIssues(tree(root).build()).slow()).isTrue();
    }

    @Test
    void leavesTheTraceNotSlowWhenNoSpanReachesTheSlowThreshold() {
        // a slow query or an error is not what the SLOW badge reports
        SpanNode child = querySpan("child", 80, Map.of("db.system", "postgresql"));
        SpanNode root = node("root")
                .durationMs(50)
                .status(SpanStatus.ERROR)
                .children(List.of(child))
                .build();

        assertThat(detector.detectIssues(tree(root).queries(1, 80L).build()).slow())
                .isFalse();
    }

    @Test
    void detectsMultipleIssuesOnSameSpan() {
        SpanNode span = node("span1")
                .kind(Span.Kind.CLIENT)
                .durationMs(200)
                .status(SpanStatus.ERROR)
                .tags(Map.of("db.system", "postgresql"))
                .build();
        TraceTree trace = tree(span).queries(1, 200L).build();

        TraceTree result = detector.detectIssues(trace);

        List<SpanIssue> issues = result.rootSpan().issues();
        assertThat(issues).hasSize(3);
        assertThat(issues)
                .extracting(SpanIssue::type)
                .containsExactlyInAnyOrder(IssueType.SLOW, IssueType.SLOW_QUERY, IssueType.ERROR);
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
}
