package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.fixtures.QueryFixtureController;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * The trace list's filters over real HTTP. Each test tags its requests with a fresh id in their path,
 * so the {@code path} filter lists its own traces among those every other test adds meanwhile.
 */
class TraceQueryIT extends TestingAppITBase {

    private final String tag = UUID.randomUUID().toString();

    @Autowired
    private PeekabootTracingProperties tracingProperties;

    /** Waits for the root: a trace lists before its spans arrive, and the filters read the root's request. */
    private String request(int status) {
        return traces.getAndAwaitComplete(QueryFixtureController.path(tag, status));
    }

    private String startTimeOf(String traceId) {
        return traces.awaitTrace(traceId, TraceApiClient.COMPLETE)
                .path("summary")
                .path("startTime")
                .asString();
    }

    private long durationMsOf(String traceId) {
        return traces.awaitTrace(traceId, TraceApiClient.COMPLETE)
                .path("summary")
                .path("durationMs")
                .asLong();
    }

    /** {@code query} names the extra parameters with {@code {placeholders}} filled from {@code values}, in order. */
    private JsonNode list(String query, Object... values) {
        List<Object> variables = new ArrayList<>();
        variables.add(QueryFixtureController.pathPrefix(tag));
        variables.addAll(Arrays.asList(values));
        return api.getJson("/peekaboot/api/traces?path={path}" + query, variables.toArray());
    }

    private List<String> listedIds(String query, Object... values) {
        return list(query, values)
                .path("traces")
                .valueStream()
                .map(TraceApiClient::traceId)
                .toList();
    }

    @Test
    void filtersByThePathsStartAndTheStatusCode() {
        String first = request(503);
        String ok = request(200);
        String notFound = request(404);
        String second = request(503);

        assertThat(listedIds("")).containsExactly(second, notFound, ok, first);
        assertThat(listedIds("&statusCode={status}", 503)).containsExactly(second, first);
    }

    /** A 5xx answer fails its request whether or not anything threw; a 4xx fails nothing. */
    @Test
    void filtersByOutcome() {
        String ok = request(200);
        String failed = request(503);
        String notFound = request(404);

        assertThat(listedIds("&outcome={outcome}", "error")).containsExactly(failed);
        assertThat(listedIds("&outcome={outcome}", "OK")).containsExactly(notFound, ok);
    }

    @Test
    void filtersByTheMatchedRoute() {
        String traceId = request(200);

        assertThat(listedIds("&route={route}", "/fixtures/query/{tag}/{status}"))
                .containsExactly(traceId);
        assertThat(listedIds("&route={route}", "/fixtures/query/{tag}")).isEmpty();
    }

    /** {@code since} is in and {@code until} is out, read off the trace's own start. */
    @Test
    void filtersBySinceAndUntil() {
        String earlier = request(200);
        String later = request(200);
        String laterStart = startTimeOf(later);

        assertThat(listedIds("&since={since}", laterStart)).containsExactly(later);
        assertThat(listedIds("&until={until}", laterStart)).containsExactly(earlier);
    }

    @Test
    void readsAnOffsetSentEncoded() {
        String traceId = request(200);

        assertThat(listedIds("&since={since}", "2000-01-01T00:00:00+01:00")).containsExactly(traceId);
    }

    /** The slow request pauses longer than the quick one took, so the bound splits them however fast the run is. */
    @Test
    void filtersByTheShortestDuration() {
        String quick = request(200);
        long quickMs = durationMsOf(quick);
        String slow = traces.getAndAwaitComplete(QueryFixtureController.path(tag, 200, quickMs + 1_200));

        assertThat(listedIds("&minDurationMs={min}", quickMs + 1)).containsExactly(slow);
        assertThat(listedIds("&minDurationMs={min}", 0)).containsExactly(slow, quick);
    }

    /**
     * One 503 pauses past the slow-trace threshold. Load can hold the quick one past it too, so the
     * Slow count is checked against each 503's own duration.
     */
    @Test
    void theFilteredCountsCountWhatEveryFilterKeeps() {
        long thresholdMs = tracingProperties.getSlowTraceThresholdMs();
        request(200);
        String quick = request(503);
        String paused = traces.getAndAwaitComplete(QueryFixtureController.path(tag, 503, thresholdMs + 200));
        List<String> failed = List.of(quick, paused);
        List<String> slow = failed.stream()
                .filter(traceId -> durationMsOf(traceId) >= thresholdMs)
                .toList();
        // the store files a trace in its buckets a moment after the trace shows complete
        failed.forEach(traceId -> traces.awaitTraceInBucket("errors", traceId));
        slow.forEach(traceId -> traces.awaitTraceInBucket("slow", traceId));

        JsonNode counts = list("&statusCode={status}", 503).path("filteredBucketCounts");

        assertThat(slow).contains(paused);
        assertThat(counts.path("all").asInt()).isEqualTo(2);
        assertThat(counts.path("errors").asInt()).isEqualTo(2);
        assertThat(counts.path("slow").asInt()).isEqualTo(slow.size());
    }
}
