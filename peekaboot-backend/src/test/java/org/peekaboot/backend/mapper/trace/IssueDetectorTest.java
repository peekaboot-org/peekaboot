package org.peekaboot.backend.mapper.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.peekaboot.backend.testsupport.SpanNodes.node;
import static org.peekaboot.backend.testsupport.TraceTrees.tree;

import io.micrometer.tracing.Span;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

    @Test
    void detectIssues_shouldDetectSlowSpan() {
        // 150ms sits between the 100ms slow and the 500ms very-slow threshold
        SpanNode span = node("span1").durationMs(150).build();
        TraceTree trace = tree(span).build();

        TraceTree result = detector.detectIssues(trace);

        assertThat(result.rootSpan().issues()).hasSize(1);
        SpanIssue issue = result.rootSpan().issues().get(0);
        assertThat(issue.type()).isEqualTo(IssueType.SLOW);
        assertThat(issue.severity()).isEqualTo(IssueSeverity.WARNING);
        assertThat(issue.message()).isEqualTo("Span took 150ms (threshold: 100ms)");
    }

    @Test
    void detectIssues_shouldDetectVerySlowSpan() {
        // 600ms is at or above the 500ms very-slow threshold
        SpanNode span = node("span1").durationMs(600).build();
        TraceTree trace = tree(span).build();

        TraceTree result = detector.detectIssues(trace);

        assertThat(result.rootSpan().issues()).hasSize(1);
        SpanIssue issue = result.rootSpan().issues().get(0);
        assertThat(issue.type()).isEqualTo(IssueType.VERY_SLOW);
        assertThat(issue.severity()).isEqualTo(IssueSeverity.ERROR);
        assertThat(issue.message()).isEqualTo("Span took 600ms (threshold: 500ms)");
    }

    @Test
    void detectIssues_shouldDetectErrorSpan() {
        SpanNode span = node("span1").durationMs(50).status(SpanStatus.ERROR).build();
        TraceTree trace = tree(span).build();

        TraceTree result = detector.detectIssues(trace);

        assertThat(result.rootSpan().issues()).hasSize(1);
        SpanIssue issue = result.rootSpan().issues().get(0);
        assertThat(issue.type()).isEqualTo(IssueType.ERROR);
        assertThat(issue.severity()).isEqualTo(IssueSeverity.ERROR);
        assertThat(issue.message()).isEqualTo("Span ended with error");
    }

    @Test
    void detectIssues_shouldUseErrorMessageFromSpanAttributeIfAvailable() {
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
    void detectIssues_shouldPreferErrorMessageFieldOverTag() {
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
    void detectIssues_shouldNotFlagResultSetSpansAsSlowQuery() {
        // datasource-proxy connection/result-set spans carry jdbc.* tags but
        // are not queries (same distinction as the trace summary)
        SpanNode span = querySpan("span1", 80, Map.of("jdbc.row-count", "10"));
        TraceTree trace = tree(span).build();

        TraceTree result = detector.detectIssues(trace);

        assertThat(result.rootSpan().issues()).noneMatch(issue -> issue.type() == IssueType.SLOW_QUERY);
    }

    @Test
    void detectIssues_shouldDetectSlowQuery() {
        // 80ms is at or above the 50ms slow-query threshold
        SpanNode span =
                querySpan("span1", 80, Map.of("db.system", "postgresql", "db.statement", "SELECT * FROM users"));
        TraceTree trace = tree(span).queries(1, 80L).build();

        TraceTree result = detector.detectIssues(trace);

        assertThat(result.rootSpan().issues()).hasSize(1);
        SpanIssue issue = result.rootSpan().issues().get(0);
        assertThat(issue.type()).isEqualTo(IssueType.SLOW_QUERY);
        assertThat(issue.severity()).isEqualTo(IssueSeverity.WARNING);
        assertThat(issue.message()).isEqualTo("Query took 80ms (threshold: 50ms)");
    }

    @Test
    void detectIssues_shouldNotDetectSlowQueryOnTheServerSideOfADbTaggedExchange() {
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
    void detectIssues_shouldNotDetectSlowQueryOnNonDbSpan() {
        // 80ms would be a slow query, but this is not a DB span
        SpanNode span =
                node("span1").durationMs(80).tags(Map.of("http.method", "GET")).build();
        TraceTree trace = tree(span).build();

        TraceTree result = detector.detectIssues(trace);

        // and no SLOW either: 80ms is under the 100ms span threshold
        assertThat(result.rootSpan().issues()).isEmpty();
    }

    @Test
    void detectIssues_shouldDetectHighQueryCountOnRootSpan() {
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
    void detectIssues_shouldNotAddHighQueryCountToChildSpans() {
        SpanNode child = querySpan("child1", 30, Map.of("db.system", "mysql"));
        SpanNode root = node("root").durationMs(50).children(List.of(child)).build();
        TraceTree trace = tree(root).queries(25, 500L).build();

        TraceTree result = detector.detectIssues(trace);

        assertThat(result.rootSpan().children().get(0).issues()).isEmpty();
    }

    @Test
    void detectIssues_shouldFlagSpanWithManyDirectQueryChildren() {
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
    void detectIssues_shouldNotFlagSpanWithQueryChildrenAtThreshold() {
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
    void detectIssues_shouldSupportCustomThresholds() {
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
    void detectIssues_shouldReturnNoIssuesWhenUnderAllThresholds() {
        SpanNode span = node("span1").durationMs(50).build();
        TraceTree trace = tree(span).build();

        TraceTree result = detector.detectIssues(trace);

        assertThat(result.rootSpan().issues()).isEmpty();
    }

    @Test
    void detectIssues_shouldProcessNestedSpansRecursively() {
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
    void detectIssues_marksTheTraceSlowWhenAnySpanIsSlowOrVerySlow() {
        SpanNode slowChild = node("child").durationMs(150).build();
        SpanNode root = node("root").durationMs(50).children(List.of(slowChild)).build();

        assertThat(detector.detectIssues(tree(root).build()).slow()).isTrue();
    }

    @Test
    void detectIssues_leavesTheTraceNotSlowWhenNoSpanReachesTheSlowThreshold() {
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
    void detectIssues_shouldDetectMultipleIssuesOnSameSpan() {
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
    void detectIssues_shouldHandleNullRootSpan() {
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
