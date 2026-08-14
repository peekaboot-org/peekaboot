package org.peekaboot.backend.domain.trace;

import org.peekaboot.backend.tracing.event.RequestCompletedEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.peekaboot.backend.domain.trace.RootActionType.HTTP_REQUEST;
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

        TraceTabSummary summary = new TraceTabSummary(
            null,
            new TraceTabSummary.SpansSummary(5, 200L, 0),
            new TraceTabSummary.QueriesSummary(2, 100L),
            new TraceTabSummary.LogsSummary(0, 0, 0)
        );

        TraceTree tree = new TraceTree(
            "trace-abc123",
            1000L,
            200L,
            TraceStatus.OK,
            HTTP_REQUEST,
            "GET /api/users",
            rootSpan,
            summary,
            Map.of("service.name", "user-service")
        );

        assertThat(tree.traceId()).isEqualTo("trace-abc123");
        assertThat(tree.rootSpan()).isEqualTo(rootSpan);
        assertThat(tree.status()).isEqualTo(TraceStatus.OK);
        assertThat(tree.summary().spans().count()).isEqualTo(5);
        assertThat(tree.inheritedAttributes()).containsEntry("service.name", "user-service");
    }

    @Test
    void traceStatus_shouldHaveAllValues() {
        assertThat(TraceStatus.values()).containsExactly(
            TraceStatus.OK,
            TraceStatus.HAS_ERRORS
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
        TraceTabSummary tabSummary = new TraceTabSummary(
            null,
            new TraceTabSummary.SpansSummary(1, 100L, 0),
            new TraceTabSummary.QueriesSummary(0, 0L),
            new TraceTabSummary.LogsSummary(0, 0, 0)
        );
        TraceTree tree = new TraceTree(
            "trace-1", 0L, 100L, TraceStatus.OK, HTTP_REQUEST, "op",
            rootSpan, tabSummary, Map.of()
        );

        TraceListSummary summary = new TraceListSummary(1, 0, 0, 100.0);

        TraceInsightsResponse response = new TraceInsightsResponse(List.of(tree), summary, BucketCounts.empty());

        assertThat(response.traces()).hasSize(1);
        assertThat(response.summary().traceCount()).isEqualTo(1);
        assertThat(response.summary().avgDurationMs()).isEqualTo(100.0);
    }

    @Test
    void httpExchange_from_mapsAllFieldsWhenPresent() {
        RequestCompletedEvent event = new RequestCompletedEvent(
            "trace-1",
            "POST", "/api/users", "sort=name",
            Map.of("Content-Type", "application/json"),
            "{\"name\":\"joe\"}", false,
            "UserController", "create",
            Map.of("sort", List.of("name")),
            Map.of("field", List.of("value")),
            List.of(new RequestCompletedEvent.UploadedFile("file", "photo.png", "image/png", 1024L)),
            201,
            Map.of("Location", "/api/users/1"),
            50L
        );

        HttpExchange exchange = HttpExchange.from(event);

        assertThat(exchange.request().method()).isEqualTo("POST");
        assertThat(exchange.request().path()).isEqualTo("/api/users");
        assertThat(exchange.request().query()).isEqualTo("sort=name");
        assertThat(exchange.request().headers()).containsEntry("Content-Type", "application/json");
        assertThat(exchange.request().body().truncated()).isFalse();
        assertThat(exchange.request().body().content()).isEqualTo("{\"name\":\"joe\"}");
        assertThat(exchange.request().controller().className()).isEqualTo("UserController");
        assertThat(exchange.request().controller().method()).isEqualTo("create");
        assertThat(exchange.request().params().query()).containsEntry("sort", List.of("name"));
        assertThat(exchange.request().params().form()).containsEntry("field", List.of("value"));
        assertThat(exchange.request().params().upload()).hasSize(1);
        assertThat(exchange.request().params().upload().getFirst().fieldName()).isEqualTo("file");
        assertThat(exchange.request().params().upload().getFirst().originalFilename()).isEqualTo("photo.png");
        assertThat(exchange.request().params().upload().getFirst().contentType()).isEqualTo("image/png");
        assertThat(exchange.request().params().upload().getFirst().size()).isEqualTo(1024L);
        assertThat(exchange.response().status()).isEqualTo(201);
        assertThat(exchange.response().headers()).containsEntry("Location", "/api/users/1");
    }

    @Test
    void httpExchange_from_defaultsNullQueryParamsToEmptyMap() {
        HttpExchange exchange = HttpExchange.from(minimalEvent(null, Map.of(), List.of(), Map.of(), Map.of()));

        assertThat(exchange.request().params().query()).isEmpty();
    }

    @Test
    void httpExchange_from_defaultsNullFormParamsToEmptyMap() {
        HttpExchange exchange = HttpExchange.from(minimalEvent(Map.of(), null, List.of(), Map.of(), Map.of()));

        assertThat(exchange.request().params().form()).isEmpty();
    }

    @Test
    void httpExchange_from_defaultsNullUploadedFilesToEmptyList() {
        HttpExchange exchange = HttpExchange.from(minimalEvent(Map.of(), Map.of(), null, Map.of(), Map.of()));

        assertThat(exchange.request().params().upload()).isEmpty();
    }

    @Test
    void httpExchange_from_defaultsNullRequestHeadersToEmptyMap() {
        HttpExchange exchange = HttpExchange.from(minimalEvent(Map.of(), Map.of(), List.of(), null, Map.of()));

        assertThat(exchange.request().headers()).isEmpty();
    }

    @Test
    void httpExchange_from_defaultsNullResponseHeadersToEmptyMap() {
        HttpExchange exchange = HttpExchange.from(minimalEvent(Map.of(), Map.of(), List.of(), Map.of(), null));

        assertThat(exchange.response().headers()).isEmpty();
    }

    private RequestCompletedEvent minimalEvent(
            Map<String, List<String>> queryParams,
            Map<String, List<String>> formParams,
            List<RequestCompletedEvent.UploadedFile> uploadedFiles,
            Map<String, String> requestHeaders,
            Map<String, String> responseHeaders) {
        return new RequestCompletedEvent(
            "trace-1",
            "GET", "/api/users", null,
            requestHeaders,
            null, false,
            "UserController", "list",
            queryParams,
            formParams,
            uploadedFiles,
            200,
            responseHeaders,
            10L
        );
    }
}
