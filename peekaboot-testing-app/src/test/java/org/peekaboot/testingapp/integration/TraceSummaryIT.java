package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.peekaboot.fixtures.OutcomeFixtureController;
import org.peekaboot.testingapp.entity.CustomerOrder;
import org.peekaboot.testingapp.repository.OrderLineRepository;
import org.peekaboot.testingapp.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** What the trace API says about a whole trace, its buckets and its outcome and why it got them. */
class TraceSummaryIT extends TestingAppITBase {

    /** Past the default repeated-query threshold of 5. */
    private static final int REPEATING_ORDERS = 6;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderLineRepository orderLineRepository;

    private static List<String> reasonKinds(JsonNode trace) {
        return trace.path("summary")
                .path("reasons")
                .valueStream()
                .map(reason -> reason.path("kind").asString())
                .toList();
    }

    private static List<String> buckets(JsonNode trace) {
        return trace.path("summary")
                .path("buckets")
                .valueStream()
                .map(JsonNode::asString)
                .toList();
    }

    /** A handler that answers 503 without throwing fails its request all the same. */
    @Test
    void aServerErrorWithoutAnExceptionIsListedInErrors() {
        String traceId = traces.get(OutcomeFixtureController.statusPath(503));

        traces.awaitTraceInBucket("errors", traceId);
    }

    /**
     * A 4xx is the caller's mistake. The store classifies a trace a moment after its root is
     * stored, so a race can let this check pass early but never fail it.
     */
    @Test
    void aClientErrorStaysOutOfErrors() {
        String traceId = traces.get(OutcomeFixtureController.statusPath(404));

        JsonNode trace = traces.awaitTrace(traceId, TraceApiClient.ROOT_SPAN_EXPORTED);
        assertThat(traces.listedTraceIds("bucket=errors")).doesNotContain(traceId);

        assertThat(trace.path("summary").path("outcome").asString()).isEqualTo("OK");
        assertThat(trace.path("summary").path("reasons").isArray()).isTrue();
        assertThat(trace.path("summary").path("reasons").isEmpty()).isTrue();
    }

    /** A request that logged an ERROR failed, though every span ended OK. */
    @Test
    void anErrorLogAloneFailsTheTrace() {
        String traceId = traces.get(OutcomeFixtureController.ERROR_LOG_PATH);

        JsonNode trace = traces.awaitTrace(traceId, TraceApiClient.ROOT_SPAN_EXPORTED);

        assertThat(trace.path("summary").path("outcome").asString()).isEqualTo("ERROR");
        assertThat(buckets(trace)).containsExactly("ERRORS");
        assertThat(trace.path("summary").path("reasons")).singleElement().satisfies(reason -> {
            assertThat(reason.path("kind").asString()).isEqualTo("ERROR_LOG");
            assertThat(reason.path("detail").asString()).isEqualTo(OutcomeFixtureController.ERROR_LOG_MESSAGE);
        });
    }

    @Test
    void aServerErrorWithoutAnExceptionFailsTheTrace() {
        String traceId = traces.get(OutcomeFixtureController.statusPath(503));

        JsonNode trace = traces.awaitTrace(traceId, TraceApiClient.ROOT_SPAN_EXPORTED);

        assertThat(trace.path("summary").path("outcome").asString()).isEqualTo("ERROR");
        assertThat(trace.path("summary").path("reasons")).singleElement().satisfies(reason -> {
            assertThat(reason.path("kind").asString()).isEqualTo("SERVER_ERROR");
            assertThat(reason.path("spanId").asString())
                    .isEqualTo(SpanTree.spanId(trace.path("data").path("rootSpan")));
            assertThat(reason.path("detail").asString()).isEqualTo("Answered with status 503.");
        });
    }

    /** /boom logs, throws and answers 500, so every failure kind names it. */
    @Test
    void aThrowingHandlerFailsForEveryReason() {
        String traceId = traces.get("/boom");

        JsonNode trace = traces.awaitTrace(
                traceId,
                TraceApiClient.ROOT_SPAN_EXPORTED.and(
                        TraceApiClient.errorLogged("order reconciliation gateway is unreachable")));

        assertThat(trace.path("summary").path("outcome").asString()).isEqualTo("ERROR");
        assertThat(reasonKinds(trace))
                .contains("EXCEPTION", "SERVER_ERROR", "ERROR_LOG")
                .doesNotContain("SLOW");
    }

    /**
     * The report sleeps past the slow-trace threshold and succeeds. The listing shows a trace once
     * its root is stored, a moment before the store classifies it, so the Slow listing is the wait.
     */
    @Test
    void aSlowReportIsSlowAndNotFailed() {
        CustomerOrder order = OrderFixtures.seedOrder(orderRepository, orderLineRepository, 1L, "WIDGET-SLOW-REASON");
        try {
            String traceId = traces.get("/api/orders/" + order.getId() + "/report");
            traces.awaitTraceInBucket("slow", traceId);

            JsonNode trace = traces.awaitTrace(traceId, TraceApiClient.ROOT_SPAN_EXPORTED);

            assertThat(trace.path("summary").path("outcome").asString()).isEqualTo("OK");
            assertThat(reasonKinds(trace)).containsExactly("SLOW");
            assertThat(buckets(trace)).containsExactly("SLOW");
        } finally {
            OrderFixtures.deleteOrder(orderRepository, orderLineRepository, order);
        }
    }

    @Test
    void aClientErrorIsAWarningOnTheRoot() {
        String traceId = traces.get(OutcomeFixtureController.statusPath(409));

        JsonNode trace = traces.awaitTrace(traceId, TraceApiClient.ROOT_SPAN_EXPORTED);

        assertThat(SpanTree.issuesOfType(trace, "CLIENT_ERROR")).singleElement().satisfies(issue -> {
            assertThat(issue.path("spanId").asString())
                    .isEqualTo(SpanTree.spanId(trace.path("data").path("rootSpan")));
            assertThat(issue.path("severity").asString()).isEqualTo("warning");
            assertThat(issue.path("value").asInt()).isEqualTo(409);
            assertThat(issue.path("threshold").asInt()).isEqualTo(400);
            assertThat(issue.path("unit").asString()).isEqualTo("status");
        });
        assertThat(trace.path("summary").path("outcome").asString()).isEqualTo("OK");
    }

    /**
     * Spring resolves a {@code ResponseStatusException} inside the dispatcher. If it marked the server
     * span ERROR for it, every 404 would fail its trace and fill Errors.
     */
    @Test
    void aNotFoundRaisedAsAnExceptionIsAClientErrorNotAFailure() {
        String traceId = traces.get("/persons/987654321");

        JsonNode trace = traces.awaitTrace(traceId, TraceApiClient.ROOT_SPAN_EXPORTED);

        assertThat(SpanTree.issuesOfType(trace, "CLIENT_ERROR")).hasSize(1);
        assertThat(trace.path("summary").path("outcome").asString()).isEqualTo("OK");
        assertThat(traces.listedTraceIds("bucket=errors")).doesNotContain(traceId);
    }

    /** The list answers with summaries unless asked for data, which keeps the dashboard's poll small. */
    @Test
    void theListingCarriesDataOnlyWhenAskedFor() {
        String traceId = traces.get(OutcomeFixtureController.statusPath(200));
        traces.awaitTrace(traceId, TraceApiClient.ROOT_SPAN_EXPORTED);

        JsonNode summaryRow = TraceApiClient.rowOf(api.getJson("/peekaboot/api/traces?limit=10000"), traceId);
        JsonNode fullRow = TraceApiClient.rowOf(api.getJson("/peekaboot/api/traces?limit=10000&view=full"), traceId);

        assertThat(summaryRow.has("data")).as("data is null, not absent").isTrue();
        assertThat(summaryRow.path("data").isNull()).isTrue();
        assertThat(fullRow.path("data").path("rootSpan").isObject()).isTrue();
        assertThat(fullRow.path("summary")).isEqualTo(summaryRow.path("summary"));
    }

    @Test
    void theSummaryNamesTheRouteTheRequestMatched() {
        String traceId = traces.get(OutcomeFixtureController.statusPath(200));

        JsonNode request = traces.awaitTrace(traceId, TraceApiClient.ROOT_SPAN_EXPORTED)
                .path("summary")
                .path("request");

        assertThat(request.path("method").asString()).isEqualTo("GET");
        assertThat(request.path("route").asString()).isEqualTo("/fixtures/status/{status}");
        assertThat(request.path("path").asString()).isEqualTo("/fixtures/status/200");
        assertThat(request.path("statusCode").asInt()).isEqualTo(200);
    }

    /** An agent reads a query's SQL off the query, and it is the statement its span carries. */
    @Test
    void everyQueryCarriesTheStatementItsSpanCarries() {
        String traceId = traces.get("/orders");

        JsonNode trace = traces.awaitTrace(traceId, TraceApiClient.ROOT_SPAN_EXPORTED);

        assertThat(trace.path("data").path("queries"))
                .isNotEmpty()
                .allSatisfy(query -> assertThat(query.path("statement"))
                        .isEqualTo(
                                SpanTree.spanWithId(trace, query.path("spanId").asString())
                                        .path("statement")));
    }

    /** The orders page loads each order's lines one query at a time, so enough orders make it an N+1. */
    @Test
    void theSummaryListsEveryIssueOnTheTreeAndCountsWhatTheDataHolds() {
        List<CustomerOrder> orders = IntStream.rangeClosed(1, REPEATING_ORDERS)
                .mapToObj(i -> OrderFixtures.seedOrder(orderRepository, orderLineRepository, 1L, "WIDGET-ISSUES-" + i))
                .toList();
        try {
            String traceId = traces.get("/orders");

            JsonNode trace = traces.awaitTrace(traceId, TraceApiClient.ROOT_SPAN_EXPORTED);

            JsonNode summary = trace.path("summary");
            List<JsonNode> repeatedOnTheTree = SpanTree.issuesOfType(trace, "REPEATED_QUERY");
            assertThat(repeatedOnTheTree).as("/orders repeats its line query").isNotEmpty();
            assertThat(summary.path("issues")
                            .valueStream()
                            .filter(issue ->
                                    "REPEATED_QUERY".equals(issue.path("type").asString())))
                    .containsExactlyElementsOf(repeatedOnTheTree);
            assertThat(summary.path("counts").path("queries").path("count").asInt())
                    .isEqualTo(trace.path("data").path("queries").size());
            assertThat(summary.path("counts").path("logs").path("count").asInt())
                    .isEqualTo(trace.path("data").path("logs").size());
            assertThat(summary.path("counts").path("spans").asInt())
                    .isEqualTo(SpanTree.names(trace).size());
        } finally {
            orders.forEach(order -> OrderFixtures.deleteOrder(orderRepository, orderLineRepository, order));
        }
    }
}
