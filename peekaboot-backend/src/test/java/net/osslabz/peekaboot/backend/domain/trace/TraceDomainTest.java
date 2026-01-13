package net.osslabz.peekaboot.backend.domain.trace;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static net.osslabz.peekaboot.backend.domain.trace.RootActionType.HTTP_REQUEST;
import static org.assertj.core.api.Assertions.assertThat;

class TraceDomainTest {

    @Test
    void spanNode_shouldHoldChildren() {
        SpanNode child = new SpanNode(
            "child-1",
            "db-query",
            "CLIENT",
            1000L,
            50L,
            "ok",
            List.of(),
            Map.of("db.statement", "SELECT * FROM users"),
            List.of(),
            List.of()
        );

        SpanNode parent = new SpanNode(
            "parent-1",
            "handle-request",
            "SERVER",
            900L,
            100L,
            "ok",
            List.of(child),
            Map.of(),
            List.of(),
            List.of()
        );

        assertThat(parent.children()).hasSize(1);
        assertThat(parent.children().get(0).spanId()).isEqualTo("child-1");
        assertThat(parent.children().get(0).name()).isEqualTo("db-query");
    }

    @Test
    void traceTree_shouldBeConstructed() {
        SpanNode rootSpan = new SpanNode(
            "span-1",
            "GET /api/users",
            "SERVER",
            1000L,
            200L,
            "ok",
            List.of(),
            Map.of("http.method", "GET"),
            List.of(),
            List.of()
        );

        TraceMetrics metrics = new TraceMetrics(5, 2, 100L, 1, 0);

        TraceTree tree = new TraceTree(
            "trace-abc123",
            1000L,
            200L,
            TraceStatus.OK,
            HTTP_REQUEST,
            "GET /api/users",
            rootSpan,
            metrics,
            Map.of("service.name", "user-service")
        );

        assertThat(tree.traceId()).isEqualTo("trace-abc123");
        assertThat(tree.rootSpan()).isEqualTo(rootSpan);
        assertThat(tree.status()).isEqualTo(TraceStatus.OK);
        assertThat(tree.metrics().totalSpans()).isEqualTo(5);
        assertThat(tree.inheritedAttributes()).containsEntry("service.name", "user-service");
    }

    @Test
    void traceStatus_shouldHaveAllValues() {
        assertThat(TraceStatus.values()).containsExactly(
            TraceStatus.OK,
            TraceStatus.HAS_ERRORS,
            TraceStatus.HAS_SLOW_SPANS
        );
    }

    @Test
    void issueType_shouldHaveAllValues() {
        assertThat(IssueType.values()).containsExactly(
            IssueType.SLOW,
            IssueType.VERY_SLOW,
            IssueType.ERROR,
            IssueType.SLOW_QUERY,
            IssueType.HIGH_QUERY_COUNT
        );
    }

    @Test
    void spanIssue_shouldHoldIssueDetails() {
        SpanIssue issue = new SpanIssue(
            IssueType.SLOW,
            "Span took 1500ms (threshold: 1000ms)",
            "warning"
        );

        assertThat(issue.type()).isEqualTo(IssueType.SLOW);
        assertThat(issue.message()).contains("1500ms");
        assertThat(issue.severity()).isEqualTo("warning");
    }

    @Test
    void traceInsightsResponse_shouldCombineTracesAndSummary() {
        SpanNode rootSpan = new SpanNode(
            "span-1", "op", "SERVER", 0L, 100L, "ok",
            List.of(), Map.of(), List.of(), List.of()
        );
        TraceMetrics metrics = new TraceMetrics(1, 0, 0L, 0, 0);
        TraceTree tree = new TraceTree(
            "trace-1", 0L, 100L, TraceStatus.OK, HTTP_REQUEST, "op",
            rootSpan, metrics, Map.of()
        );

        TraceSummary summary = new TraceSummary(1, 0, 0, 100.0);

        TraceInsightsResponse response = new TraceInsightsResponse(List.of(tree), summary);

        assertThat(response.traces()).hasSize(1);
        assertThat(response.summary().totalTraces()).isEqualTo(1);
        assertThat(response.summary().avgDurationMs()).isEqualTo(100.0);
    }
}
