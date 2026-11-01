package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.peekaboot.fixtures.HeldRequestController;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Whether a trace's root has arrived, as the API reports it over HTTP for a request still running and for one continuing a caller's trace. */
class TraceCompletenessIT extends TestingAppITBase {

    @Autowired
    private HeldRequestController held;

    /** Read while the request is still held, the trace is its arrived child alone, served as such rather than rooted at it. */
    @Test
    void aTraceWhoseRequestIsStillRunningIsServedAsInProgress() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            CompletableFuture<HttpResponse<String>> response = client.sendAsync(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + HeldRequestController.PATH))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            String traceId = held.awaitHeldTraceId(Duration.ofSeconds(15));

            JsonNode inFlight = traces.awaitTrace(
                    traceId, trace -> SpanTree.orphanNames(trace).contains(HeldRequestController.CHILD_SPAN));

            assertThat(inFlight.path("summary").path("complete").asBoolean(true))
                    .isFalse();
            assertThat(inFlight.path("summary").path("outcome").asString("")).isEqualTo("IN_PROGRESS");
            assertThat(inFlight.path("data").path("rootSpan").isNull())
                    .as("rootSpan is null, not absent")
                    .isTrue();

            held.release();
            assertThat(response.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(200);

            JsonNode complete = traces.awaitTrace(traceId, TraceApiClient.COMPLETE);
            assertThat(complete.path("summary").path("outcome").asString("")).isEqualTo("OK");
            assertThat(complete.path("data").path("orphans").isArray())
                    .as("orphans is an array, not absent")
                    .isTrue();
            assertThat(complete.path("data").path("orphans")).isEmpty();
            assertThat(SpanTree.names(complete)).contains(HeldRequestController.CHILD_SPAN);
        }
    }

    /** A caller's traceparent makes the inbound request's span the root here, though its parent is never exported. */
    @Test
    void anInboundRequestContinuingACallersTraceIsComplete() {
        String callerTraceId = HexFormat.of().formatHex(randomBytes(16));
        String callerSpanId = HexFormat.of().formatHex(randomBytes(8));

        api.restClient()
                .get()
                .uri("/persons")
                .header("traceparent", "00-" + callerTraceId + "-" + callerSpanId + "-01")
                .retrieve()
                .toBodilessEntity();

        JsonNode trace = traces.awaitTrace(callerTraceId, TraceApiClient.ROOT_SPAN_EXPORTED);
        assertThat(trace.path("summary").path("complete").asBoolean(false)).isTrue();
        assertThat(trace.path("data").path("rootSpan").path("kind").asString(""))
                .isEqualTo("SERVER");
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        ThreadLocalRandom.current().nextBytes(bytes);
        return bytes;
    }
}
