package net.osslabz.peekaboot.backend.mapper.trace;

import net.osslabz.peekaboot.backend.config.UiTracingProperties;
import net.osslabz.peekaboot.backend.domain.trace.IssueType;
import net.osslabz.peekaboot.backend.domain.trace.RootActionType;
import net.osslabz.peekaboot.backend.domain.trace.SpanIssue;
import net.osslabz.peekaboot.backend.domain.trace.SpanNode;
import net.osslabz.peekaboot.backend.domain.trace.TraceTabSummary;
import net.osslabz.peekaboot.backend.domain.trace.TraceStatus;
import net.osslabz.peekaboot.backend.domain.trace.TraceTree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
        // Given: A span with duration 150ms (between 100-499ms threshold)
        SpanNode span = createSpan("span1", 150, "OK", Map.of(), List.of());
        TraceTree trace = createTrace(span, createSummary(1, 0, 0L, 0));

        // When
        TraceTree result = detector.detectIssues(trace);

        // Then
        assertThat(result.rootSpan().issues()).hasSize(1);
        SpanIssue issue = result.rootSpan().issues().get(0);
        assertThat(issue.type()).isEqualTo(IssueType.SLOW);
        assertThat(issue.severity()).isEqualTo("warning");
        assertThat(issue.message()).isEqualTo("Span took 150ms (threshold: 100ms)");
    }

    @Test
    void detectIssues_shouldDetectVerySlowSpan() {
        // Given: A span with duration 600ms (>= 500ms threshold)
        SpanNode span = createSpan("span1", 600, "OK", Map.of(), List.of());
        TraceTree trace = createTrace(span, createSummary(1, 0, 0L, 0));

        // When
        TraceTree result = detector.detectIssues(trace);

        // Then
        assertThat(result.rootSpan().issues()).hasSize(1);
        SpanIssue issue = result.rootSpan().issues().get(0);
        assertThat(issue.type()).isEqualTo(IssueType.VERY_SLOW);
        assertThat(issue.severity()).isEqualTo("error");
        assertThat(issue.message()).isEqualTo("Span took 600ms (threshold: 500ms)");
    }

    @Test
    void detectIssues_shouldNotAddBothSlowAndVerySlowForSameSpan() {
        // Given: A very slow span (600ms) should NOT also be marked as SLOW
        SpanNode span = createSpan("span1", 600, "OK", Map.of(), List.of());
        TraceTree trace = createTrace(span, createSummary(1, 0, 0L, 0));

        // When
        TraceTree result = detector.detectIssues(trace);

        // Then: Only VERY_SLOW, not both SLOW and VERY_SLOW
        assertThat(result.rootSpan().issues()).hasSize(1);
        assertThat(result.rootSpan().issues().get(0).type()).isEqualTo(IssueType.VERY_SLOW);
    }

    @Test
    void detectIssues_shouldDetectErrorSpan() {
        // Given: A span with ERROR status
        SpanNode span = createSpan("span1", 50, "ERROR", Map.of(), List.of());
        TraceTree trace = createTrace(span, createSummary(1, 0, 0L, 0));

        // When
        TraceTree result = detector.detectIssues(trace);

        // Then
        assertThat(result.rootSpan().issues()).hasSize(1);
        SpanIssue issue = result.rootSpan().issues().get(0);
        assertThat(issue.type()).isEqualTo(IssueType.ERROR);
        assertThat(issue.severity()).isEqualTo("error");
        assertThat(issue.message()).isEqualTo("Span ended with error");
    }

    @Test
    void detectIssues_shouldUseErrorMessageFromSpanAttributeIfAvailable() {
        // Given: A span with ERROR status and an error message in attributes
        SpanNode span = createSpan("span1", 50, "ERROR",
                Map.of("error.message", "Connection refused"), List.of());
        TraceTree trace = createTrace(span, createSummary(1, 0, 0L, 0));

        // When
        TraceTree result = detector.detectIssues(trace);

        // Then
        SpanIssue issue = result.rootSpan().issues().get(0);
        assertThat(issue.type()).isEqualTo(IssueType.ERROR);
        assertThat(issue.message()).isEqualTo("Connection refused");
    }

    @Test
    void detectIssues_shouldDetectSlowQuery() {
        // Given: A DB span with duration 80ms (>= 50ms threshold)
        SpanNode span = createSpan("span1", 80, "OK",
                Map.of("db.system", "postgresql", "db.statement", "SELECT * FROM users"), List.of());
        TraceTree trace = createTrace(span, createSummary(1, 1, 80L, 0));

        // When
        TraceTree result = detector.detectIssues(trace);

        // Then
        assertThat(result.rootSpan().issues()).hasSize(1);
        SpanIssue issue = result.rootSpan().issues().get(0);
        assertThat(issue.type()).isEqualTo(IssueType.SLOW_QUERY);
        assertThat(issue.severity()).isEqualTo("warning");
        assertThat(issue.message()).isEqualTo("Query took 80ms (threshold: 50ms)");
    }

    @Test
    void detectIssues_shouldNotDetectSlowQueryOnNonDbSpan() {
        // Given: A non-DB span with duration 80ms (would be slow if it were a DB span)
        SpanNode span = createSpan("span1", 80, "OK",
                Map.of("http.method", "GET"), List.of());
        TraceTree trace = createTrace(span, createSummary(1, 0, 0L, 0));

        // When
        TraceTree result = detector.detectIssues(trace);

        // Then: No SLOW_QUERY issue (and no SLOW issue since 80 < 100)
        assertThat(result.rootSpan().issues()).isEmpty();
    }

    @Test
    void detectIssues_shouldDetectHighQueryCountOnRootSpan() {
        // Given: A trace with 25 DB queries (> 20 threshold)
        SpanNode child = createSpan("child1", 30, "OK", Map.of("db.system", "mysql"), List.of());
        SpanNode root = createSpan("root", 50, "OK", Map.of(), List.of(child));
        TraceTree trace = createTrace(root, createSummary(2, 25, 500L, 0));

        // When
        TraceTree result = detector.detectIssues(trace);

        // Then: HIGH_QUERY_COUNT issue on root span
        assertThat(result.rootSpan().issues()).hasSize(1);
        SpanIssue issue = result.rootSpan().issues().get(0);
        assertThat(issue.type()).isEqualTo(IssueType.HIGH_QUERY_COUNT);
        assertThat(issue.severity()).isEqualTo("warning");
        assertThat(issue.message()).isEqualTo("Trace has 25 database queries (threshold: 20)");
    }

    @Test
    void detectIssues_shouldNotAddHighQueryCountToChildSpans() {
        // Given: A trace with high query count but child span should not have the issue
        SpanNode child = createSpan("child1", 30, "OK", Map.of("db.system", "mysql"), List.of());
        SpanNode root = createSpan("root", 50, "OK", Map.of(), List.of(child));
        TraceTree trace = createTrace(root, createSummary(2, 25, 500L, 0));

        // When
        TraceTree result = detector.detectIssues(trace);

        // Then: HIGH_QUERY_COUNT only on root, not on child
        assertThat(result.rootSpan().children().get(0).issues()).isEmpty();
    }

    @Test
    void detectIssues_shouldFlagSpanWithManyDirectQueryChildren() {
        // Default highQueryCountThreshold is 5; six direct query children exceed it
        List<SpanNode> queries = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            queries.add(createSpan("q" + i, 10, "OK", Map.of("jdbc.query[0]", "SELECT " + i), List.of()));
        }
        SpanNode service = createSpan("service", 80, "OK", Map.of(), List.copyOf(queries));
        SpanNode root = createSpan("root", 90, "OK", Map.of(), List.of(service));
        TraceTree trace = createTrace(root, createSummary(8, 6, 60L, 0));

        TraceTree result = detector.detectIssues(trace);

        SpanNode serviceNode = result.rootSpan().children().get(0);
        assertThat(serviceNode.issues())
                .anyMatch(issue -> issue.type() == IssueType.HIGH_QUERY_COUNT);
    }

    @Test
    void detectIssues_shouldNotFlagSpanWithQueryChildrenAtThreshold() {
        List<SpanNode> queries = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            queries.add(createSpan("q" + i, 10, "OK", Map.of("jdbc.query[0]", "SELECT " + i), List.of()));
        }
        SpanNode service = createSpan("service", 80, "OK", Map.of(), List.copyOf(queries));
        SpanNode root = createSpan("root", 90, "OK", Map.of(), List.of(service));
        TraceTree trace = createTrace(root, createSummary(7, 5, 50L, 0));

        TraceTree result = detector.detectIssues(trace);

        SpanNode serviceNode = result.rootSpan().children().get(0);
        assertThat(serviceNode.issues())
                .noneMatch(issue -> issue.type() == IssueType.HIGH_QUERY_COUNT);
    }

    @Test
    void detectIssues_shouldSupportCustomThresholds() {
        // Given: Custom thresholds - slow at 200ms instead of 100ms
        properties.setSlowSpanThresholdMs(200);
        properties.setVerySlowSpanThresholdMs(1000);

        SpanNode span = createSpan("span1", 150, "OK", Map.of(), List.of());
        TraceTree trace = createTrace(span, createSummary(1, 0, 0L, 0));

        // When
        TraceTree result = detector.detectIssues(trace);

        // Then: 150ms is under the 200ms threshold, so no issue
        assertThat(result.rootSpan().issues()).isEmpty();
    }

    @Test
    void detectIssues_shouldReturnNoIssuesWhenUnderAllThresholds() {
        // Given: A fast span with no errors
        SpanNode span = createSpan("span1", 50, "OK", Map.of(), List.of());
        TraceTree trace = createTrace(span, createSummary(1, 0, 0L, 0));

        // When
        TraceTree result = detector.detectIssues(trace);

        // Then
        assertThat(result.rootSpan().issues()).isEmpty();
    }

    @Test
    void detectIssues_shouldProcessNestedSpansRecursively() {
        // Given: A tree with nested slow spans
        SpanNode grandchild = createSpan("gc", 200, "OK", Map.of(), List.of());
        SpanNode child = createSpan("child", 300, "OK", Map.of(), List.of(grandchild));
        SpanNode root = createSpan("root", 50, "OK", Map.of(), List.of(child));
        TraceTree trace = createTrace(root, createSummary(3, 0, 0L, 0));

        // When
        TraceTree result = detector.detectIssues(trace);

        // Then: Root has no issues, child and grandchild have SLOW issues
        assertThat(result.rootSpan().issues()).isEmpty();
        assertThat(result.rootSpan().children().get(0).issues()).hasSize(1);
        assertThat(result.rootSpan().children().get(0).issues().get(0).type()).isEqualTo(IssueType.SLOW);
        assertThat(result.rootSpan().children().get(0).children().get(0).issues()).hasSize(1);
        assertThat(result.rootSpan().children().get(0).children().get(0).issues().get(0).type()).isEqualTo(IssueType.SLOW);
    }

    @Test
    void detectIssues_shouldDetectMultipleIssuesOnSameSpan() {
        // Given: A slow DB span with an error (multiple issues)
        SpanNode span = createSpan("span1", 200, "ERROR",
                Map.of("db.system", "postgresql"), List.of());
        TraceTree trace = createTrace(span, createSummary(1, 1, 200L, 1));

        // When
        TraceTree result = detector.detectIssues(trace);

        // Then: Should have SLOW, SLOW_QUERY, and ERROR issues
        List<SpanIssue> issues = result.rootSpan().issues();
        assertThat(issues).hasSize(3);
        assertThat(issues).extracting(SpanIssue::type)
                .containsExactlyInAnyOrder(IssueType.SLOW, IssueType.SLOW_QUERY, IssueType.ERROR);
    }

    @Test
    void detectIssues_shouldPreserveExistingSpanProperties() {
        // Given: A span with various properties
        SpanNode span = new SpanNode(
                "span-id-123",
                "my-operation",
                "CLIENT",
                1000L,
                150L,
                "OK",
                List.of(),
                Map.of("custom.attr", "value"),
                List.of(),
                List.of()
        );
        TraceTree trace = createTrace(span, createSummary(1, 0, 0L, 0));

        // When
        TraceTree result = detector.detectIssues(trace);

        // Then: All properties preserved
        SpanNode resultSpan = result.rootSpan();
        assertThat(resultSpan.spanId()).isEqualTo("span-id-123");
        assertThat(resultSpan.name()).isEqualTo("my-operation");
        assertThat(resultSpan.kind()).isEqualTo("CLIENT");
        assertThat(resultSpan.startTimeMs()).isEqualTo(1000L);
        assertThat(resultSpan.durationMs()).isEqualTo(150L);
        assertThat(resultSpan.status()).isEqualTo("OK");
        assertThat(resultSpan.tags()).containsEntry("custom.attr", "value");
    }

    @Test
    void detectIssues_shouldHandleNullRootSpan() {
        // Given: A trace with no root span
        TraceTree trace = new TraceTree(
                "trace1", 0, 0, TraceStatus.OK, RootActionType.UNKNOWN, null, null,
                createSummary(0, 0, 0L, 0), Map.of()
        );

        // When
        TraceTree result = detector.detectIssues(trace);

        // Then
        assertThat(result.rootSpan()).isNull();
    }

    private SpanNode createSpan(String spanId, long durationMs, String status,
                                 Map<String, Object> tags, List<SpanNode> children) {
        return new SpanNode(spanId, "test-op", "SERVER", 0, durationMs, status, children, tags, List.of(), List.of());
    }

    private TraceTree createTrace(SpanNode rootSpan, TraceTabSummary summary) {
        return new TraceTree(
                "trace-1", 0, rootSpan != null ? rootSpan.durationMs() : 0,
                TraceStatus.OK, RootActionType.UNKNOWN, rootSpan != null ? rootSpan.name() : null,
                rootSpan, summary, Map.of()
        );
    }

    private TraceTabSummary createSummary(int spanCount, int queryCount, long queryDurationMs, int errorCount) {
        return new TraceTabSummary(
                null,
                new TraceTabSummary.SpansSummary(spanCount, 0L, errorCount),
                new TraceTabSummary.QueriesSummary(queryCount, queryDurationMs),
                new TraceTabSummary.LogsSummary(0, 0, 0)
        );
    }
}
