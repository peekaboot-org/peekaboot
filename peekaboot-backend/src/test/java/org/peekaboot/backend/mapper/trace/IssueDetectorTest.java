package org.peekaboot.backend.mapper.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.config.UiTracingProperties;
import org.peekaboot.backend.domain.trace.IssueType;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.peekaboot.backend.domain.trace.SpanIssue;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.TraceLog;
import org.peekaboot.backend.domain.trace.TraceStatus;
import org.peekaboot.backend.domain.trace.TraceTabSummary;
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
        SpanNode span = createSpan("span1", 150, "OK", Map.of(), List.of());
        TraceTree trace = createTrace(span, createSummary(1, 0, 0L, 0));

        TraceTree result = detector.detectIssues(trace);

        assertThat(result.rootSpan().issues()).hasSize(1);
        SpanIssue issue = result.rootSpan().issues().get(0);
        assertThat(issue.type()).isEqualTo(IssueType.SLOW);
        assertThat(issue.severity()).isEqualTo("warning");
        assertThat(issue.message()).isEqualTo("Span took 150ms (threshold: 100ms)");
    }

    @Test
    void detectIssues_shouldDetectVerySlowSpan() {
        // 600ms is at or above the 500ms very-slow threshold
        SpanNode span = createSpan("span1", 600, "OK", Map.of(), List.of());
        TraceTree trace = createTrace(span, createSummary(1, 0, 0L, 0));

        TraceTree result = detector.detectIssues(trace);

        assertThat(result.rootSpan().issues()).hasSize(1);
        SpanIssue issue = result.rootSpan().issues().get(0);
        assertThat(issue.type()).isEqualTo(IssueType.VERY_SLOW);
        assertThat(issue.severity()).isEqualTo("error");
        assertThat(issue.message()).isEqualTo("Span took 600ms (threshold: 500ms)");
    }

    @Test
    void detectIssues_shouldNotAddBothSlowAndVerySlowForSameSpan() {
        SpanNode span = createSpan("span1", 600, "OK", Map.of(), List.of());
        TraceTree trace = createTrace(span, createSummary(1, 0, 0L, 0));

        TraceTree result = detector.detectIssues(trace);

        assertThat(result.rootSpan().issues()).hasSize(1);
        assertThat(result.rootSpan().issues().get(0).type()).isEqualTo(IssueType.VERY_SLOW);
    }

    @Test
    void detectIssues_shouldDetectErrorSpan() {
        SpanNode span = createSpan("span1", 50, "ERROR", Map.of(), List.of());
        TraceTree trace = createTrace(span, createSummary(1, 0, 0L, 0));

        TraceTree result = detector.detectIssues(trace);

        assertThat(result.rootSpan().issues()).hasSize(1);
        SpanIssue issue = result.rootSpan().issues().get(0);
        assertThat(issue.type()).isEqualTo(IssueType.ERROR);
        assertThat(issue.severity()).isEqualTo("error");
        assertThat(issue.message()).isEqualTo("Span ended with error");
    }

    @Test
    void detectIssues_shouldUseErrorMessageFromSpanAttributeIfAvailable() {
        SpanNode span = createSpan("span1", 50, "ERROR", Map.of("error.message", "Connection refused"), List.of());
        TraceTree trace = createTrace(span, createSummary(1, 0, 0L, 0));

        TraceTree result = detector.detectIssues(trace);

        SpanIssue issue = result.rootSpan().issues().get(0);
        assertThat(issue.type()).isEqualTo(IssueType.ERROR);
        assertThat(issue.message()).isEqualTo("Connection refused");
    }

    @Test
    void detectIssues_shouldPreferErrorMessageFieldOverTag() {
        // The exporter stores the error in SpanNode.errorMessage; it never
        // writes an error.message tag
        SpanNode span = new SpanNode(
                "span1",
                "test-op",
                "SERVER",
                0,
                50,
                "ERROR",
                List.of(),
                Map.of(),
                List.of(),
                List.of(),
                0,
                "Connection refused: db:5432",
                "java.net.ConnectException",
                null);
        TraceTree trace = createTrace(span, createSummary(1, 0, 0L, 1));

        TraceTree result = detector.detectIssues(trace);

        SpanIssue issue = result.rootSpan().issues().get(0);
        assertThat(issue.type()).isEqualTo(IssueType.ERROR);
        assertThat(issue.message()).isEqualTo("Connection refused: db:5432");
    }

    @Test
    void detectIssues_shouldNotFlagResultSetSpansAsSlowQuery() {
        // datasource-proxy connection/result-set spans carry jdbc.* tags but
        // are not queries (same distinction as the trace summary)
        SpanNode span = createSpan("span1", 80, "OK", Map.of("jdbc.row-count", "10"), List.of());
        TraceTree trace = createTrace(span, createSummary(1, 0, 0L, 0));

        TraceTree result = detector.detectIssues(trace);

        assertThat(result.rootSpan().issues()).noneMatch(issue -> issue.type() == IssueType.SLOW_QUERY);
    }

    @Test
    void detectIssues_shouldDetectSlowQuery() {
        // 80ms is at or above the 50ms slow-query threshold
        SpanNode span = createSpan(
                "span1", 80, "OK", Map.of("db.system", "postgresql", "db.statement", "SELECT * FROM users"), List.of());
        TraceTree trace = createTrace(span, createSummary(1, 1, 80L, 0));

        TraceTree result = detector.detectIssues(trace);

        assertThat(result.rootSpan().issues()).hasSize(1);
        SpanIssue issue = result.rootSpan().issues().get(0);
        assertThat(issue.type()).isEqualTo(IssueType.SLOW_QUERY);
        assertThat(issue.severity()).isEqualTo("warning");
        assertThat(issue.message()).isEqualTo("Query took 80ms (threshold: 50ms)");
    }

    @Test
    void detectIssues_shouldNotDetectSlowQueryOnNonDbSpan() {
        // 80ms would be a slow query, but this is not a DB span
        SpanNode span = createSpan("span1", 80, "OK", Map.of("http.method", "GET"), List.of());
        TraceTree trace = createTrace(span, createSummary(1, 0, 0L, 0));

        TraceTree result = detector.detectIssues(trace);

        // and no SLOW either: 80ms is under the 100ms span threshold
        assertThat(result.rootSpan().issues()).isEmpty();
    }

    @Test
    void detectIssues_shouldDetectHighQueryCountOnRootSpan() {
        // 25 queries: over the 20-query trace threshold
        SpanNode child = createSpan("child1", 30, "OK", Map.of("db.system", "mysql"), List.of());
        SpanNode root = createSpan("root", 50, "OK", Map.of(), List.of(child));
        TraceTree trace = createTrace(root, createSummary(2, 25, 500L, 0));

        TraceTree result = detector.detectIssues(trace);

        assertThat(result.rootSpan().issues()).hasSize(1);
        SpanIssue issue = result.rootSpan().issues().get(0);
        assertThat(issue.type()).isEqualTo(IssueType.HIGH_QUERY_COUNT);
        assertThat(issue.severity()).isEqualTo("warning");
        assertThat(issue.message()).isEqualTo("Trace has 25 database queries (threshold: 20)");
    }

    @Test
    void detectIssues_shouldNotAddHighQueryCountToChildSpans() {
        SpanNode child = createSpan("child1", 30, "OK", Map.of("db.system", "mysql"), List.of());
        SpanNode root = createSpan("root", 50, "OK", Map.of(), List.of(child));
        TraceTree trace = createTrace(root, createSummary(2, 25, 500L, 0));

        TraceTree result = detector.detectIssues(trace);

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
        assertThat(serviceNode.issues()).anyMatch(issue -> issue.type() == IssueType.HIGH_QUERY_COUNT);
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
        assertThat(serviceNode.issues()).noneMatch(issue -> issue.type() == IssueType.HIGH_QUERY_COUNT);
    }

    @Test
    void detectIssues_shouldSupportCustomThresholds() {
        // slow threshold raised to 200ms
        properties.setSlowSpanThresholdMs(200);
        properties.setVerySlowSpanThresholdMs(1000);

        SpanNode span = createSpan("span1", 150, "OK", Map.of(), List.of());
        TraceTree trace = createTrace(span, createSummary(1, 0, 0L, 0));

        TraceTree result = detector.detectIssues(trace);

        // 150ms is under the raised threshold
        assertThat(result.rootSpan().issues()).isEmpty();
    }

    @Test
    void detectIssues_shouldReturnNoIssuesWhenUnderAllThresholds() {
        SpanNode span = createSpan("span1", 50, "OK", Map.of(), List.of());
        TraceTree trace = createTrace(span, createSummary(1, 0, 0L, 0));

        TraceTree result = detector.detectIssues(trace);

        assertThat(result.rootSpan().issues()).isEmpty();
    }

    @Test
    void detectIssues_shouldProcessNestedSpansRecursively() {
        SpanNode grandchild = createSpan("gc", 200, "OK", Map.of(), List.of());
        SpanNode child = createSpan("child", 300, "OK", Map.of(), List.of(grandchild));
        SpanNode root = createSpan("root", 50, "OK", Map.of(), List.of(child));
        TraceTree trace = createTrace(root, createSummary(3, 0, 0L, 0));

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
    void detectIssues_shouldDetectMultipleIssuesOnSameSpan() {
        SpanNode span = createSpan("span1", 200, "ERROR", Map.of("db.system", "postgresql"), List.of());
        TraceTree trace = createTrace(span, createSummary(1, 1, 200L, 1));

        TraceTree result = detector.detectIssues(trace);

        List<SpanIssue> issues = result.rootSpan().issues();
        assertThat(issues).hasSize(3);
        assertThat(issues)
                .extracting(SpanIssue::type)
                .containsExactlyInAnyOrder(IssueType.SLOW, IssueType.SLOW_QUERY, IssueType.ERROR);
    }

    @Test
    void detectIssues_shouldPreserveExistingSpanProperties() {
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
                List.of());
        TraceTree trace = createTrace(span, createSummary(1, 0, 0L, 0));

        TraceTree result = detector.detectIssues(trace);

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
    void detectIssues_shouldPreserveErrorAndRemoteServiceProperties() {
        // a span using the full-field constructor, so errorMessage/
        // errorClass/remoteServiceName/creationOrder are all set to real values
        SpanNode span = new SpanNode(
                "span-id-456",
                "remote-call",
                "CLIENT",
                1000L,
                50L,
                "ERROR",
                List.of(),
                Map.of(),
                List.of(),
                List.of(),
                42L,
                "boom",
                "java.lang.RuntimeException",
                "orders-service",
                null);
        TraceTree trace = createTrace(span, createSummary(1, 0, 0L, 0));

        TraceTree result = detector.detectIssues(trace);

        SpanNode resultSpan = result.rootSpan();
        assertThat(resultSpan.creationOrder()).isEqualTo(42L);
        assertThat(resultSpan.errorMessage()).isEqualTo("boom");
        assertThat(resultSpan.errorClass()).isEqualTo("java.lang.RuntimeException");
        assertThat(resultSpan.remoteServiceName()).isEqualTo("orders-service");
    }

    @Test
    void detectIssues_shouldPreserveSpanLogs() {
        List<TraceLog> childLogs = List.of(new TraceLog(
                "child1", Instant.parse("2026-01-01T00:00:00Z"), "DEBUG", "ChildLogger", "child log", "main"));
        List<TraceLog> rootLogs = List.of(
                new TraceLog("span1", Instant.parse("2026-01-01T00:00:01Z"), "INFO", "RootLogger", "root log", "main"));
        SpanNode child = createSpan("child1", 10, "OK", Map.of(), List.of()).withLogs(childLogs);
        SpanNode root = createSpan("span1", 50, "OK", Map.of(), List.of(child)).withLogs(rootLogs);
        TraceTree trace = createTrace(root, createSummary(2, 0, 0L, 0));

        TraceTree result = detector.detectIssues(trace);

        assertThat(result.rootSpan().logs()).isEqualTo(rootLogs);
        assertThat(result.rootSpan().children().get(0).logs()).isEqualTo(childLogs);
    }

    @Test
    void detectIssues_shouldHandleNullRootSpan() {
        TraceTree trace = new TraceTree(
                "trace1",
                0,
                0,
                TraceStatus.OK,
                RootActionType.UNKNOWN,
                null,
                null,
                createSummary(0, 0, 0L, 0),
                Map.of(),
                null,
                null,
                null,
                false);

        TraceTree result = detector.detectIssues(trace);

        assertThat(result.rootSpan()).isNull();
    }

    @Test
    void detectIssues_preservesTheTruncatedFlag() {
        // a trace the store marked truncated because the span cap dropped real spans
        SpanNode span = createSpan("span1", 50, "OK", Map.of(), List.of());
        TraceTree trace = new TraceTree(
                "trace-1",
                0,
                50,
                TraceStatus.OK,
                RootActionType.UNKNOWN,
                "test-op",
                span,
                createSummary(1, 0, 0L, 0),
                Map.of(),
                null,
                null,
                null,
                true);

        TraceTree result = detector.detectIssues(trace);

        // rebuilding the tree around the processed span tree must not silently
        // reset the flag - a shortened trace must never look complete again
        assertThat(result.truncated()).isTrue();
    }

    private SpanNode createSpan(
            String spanId, long durationMs, String status, Map<String, Object> tags, List<SpanNode> children) {
        return new SpanNode(spanId, "test-op", "SERVER", 0, durationMs, status, children, tags, List.of(), List.of());
    }

    private TraceTree createTrace(SpanNode rootSpan, TraceTabSummary summary) {
        return new TraceTree(
                "trace-1",
                0,
                rootSpan != null ? rootSpan.durationMs() : 0,
                TraceStatus.OK,
                RootActionType.UNKNOWN,
                rootSpan != null ? rootSpan.name() : null,
                rootSpan,
                summary,
                Map.of(),
                null,
                null,
                null,
                false);
    }

    private TraceTabSummary createSummary(int spanCount, int queryCount, long queryDurationMs, int errorCount) {
        return new TraceTabSummary(
                null,
                new TraceTabSummary.SpansSummary(spanCount, 0L, errorCount),
                new TraceTabSummary.QueriesSummary(queryCount, queryDurationMs),
                new TraceTabSummary.LogsSummary(0, 0, 0));
    }
}
