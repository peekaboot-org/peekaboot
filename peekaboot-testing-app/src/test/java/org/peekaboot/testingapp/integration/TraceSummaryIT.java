package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.peekaboot.fixtures.OutcomeFixtureController;
import org.peekaboot.testingapp.entity.CustomerOrder;
import org.peekaboot.testingapp.repository.OrderLineRepository;
import org.peekaboot.testingapp.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** What the trace API says about a whole trace, its buckets and its outcome and why it got them. */
class TraceSummaryIT extends TestingAppITBase {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderLineRepository orderLineRepository;

    private static List<String> reasonKinds(JsonNode trace) {
        return trace.path("reasons")
                .valueStream()
                .map(reason -> reason.path("kind").asString())
                .toList();
    }

    private static List<String> buckets(JsonNode trace) {
        return trace.path("buckets").valueStream().map(JsonNode::asString).toList();
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

        traces.awaitTrace(traceId, TraceApiClient.ROOT_SPAN_EXPORTED);
        assertThat(traces.listedTraceIds("bucket=errors")).doesNotContain(traceId);

        JsonNode trace = traces.awaitTrace(traceId, TraceApiClient.ROOT_SPAN_EXPORTED);
        assertThat(trace.path("outcome").asString()).isEqualTo("OK");
        assertThat(trace.path("reasons").isArray()).isTrue();
        assertThat(trace.path("reasons").isEmpty()).isTrue();
    }

    /** A request that logged an ERROR failed, though every span ended OK. */
    @Test
    void anErrorLogAloneFailsTheTrace() {
        String traceId = traces.get(OutcomeFixtureController.ERROR_LOG_PATH);

        JsonNode trace = traces.awaitTrace(traceId, TraceApiClient.ROOT_SPAN_EXPORTED);

        assertThat(trace.path("outcome").asString()).isEqualTo("ERROR");
        assertThat(buckets(trace)).containsExactly("ERRORS");
        assertThat(trace.path("reasons")).singleElement().satisfies(reason -> {
            assertThat(reason.path("kind").asString()).isEqualTo("ERROR_LOG");
            assertThat(reason.path("detail").asString()).isEqualTo(OutcomeFixtureController.ERROR_LOG_MESSAGE);
        });
    }

    @Test
    void aServerErrorWithoutAnExceptionFailsTheTrace() {
        String traceId = traces.get(OutcomeFixtureController.statusPath(503));

        JsonNode trace = traces.awaitTrace(traceId, TraceApiClient.ROOT_SPAN_EXPORTED);

        assertThat(trace.path("outcome").asString()).isEqualTo("ERROR");
        assertThat(trace.path("reasons")).singleElement().satisfies(reason -> {
            assertThat(reason.path("kind").asString()).isEqualTo("SERVER_ERROR");
            assertThat(reason.path("spanId").asString()).isEqualTo(SpanTree.spanId(trace.path("rootSpan")));
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

        assertThat(trace.path("outcome").asString()).isEqualTo("ERROR");
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

            assertThat(trace.path("outcome").asString()).isEqualTo("OK");
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

        JsonNode issue = SpanTree.issuesOfType(trace, "CLIENT_ERROR").getFirst();
        assertThat(issue.path("spanId").asString()).isEqualTo(SpanTree.spanId(trace.path("rootSpan")));
        assertThat(issue.path("value").asInt()).isEqualTo(409);
        assertThat(issue.path("unit").asString()).isEqualTo("status");
        assertThat(trace.path("outcome").asString()).isEqualTo("OK");
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
        assertThat(trace.path("outcome").asString()).isEqualTo("OK");
        assertThat(traces.listedTraceIds("bucket=errors")).doesNotContain(traceId);
    }
}
