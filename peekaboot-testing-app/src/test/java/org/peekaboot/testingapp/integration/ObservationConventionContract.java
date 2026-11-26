package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.peekaboot.fixtures.HeldRequestController;
import org.peekaboot.fixtures.OutcomeFixtureController;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * What Peekaboot shows of a request, whichever observation conventions the application chose:
 * the trace's request and verdict, its logs, and the Insights panels the server-request, JVM
 * and CPU meters fill. Each subclass boots the app under one setup in a context of its own and
 * first proves that setup is the one in effect, so a green run cannot be the other convention's.
 */
abstract class ObservationConventionContract extends TestingAppITBase {

    protected static final String MICROMETER_SERVER_METER = "http.server.requests";

    protected static final String OPEN_TELEMETRY_SERVER_METER = "http.server.request.duration";

    private static final List<String> HTTP_SERIES = List.of(
            "http-throughput.requests",
            "http-throughput.client-errors",
            "http-throughput.server-errors",
            "http-latency.avg",
            "http-latency.max");

    private static final List<String> JVM_AND_CPU_SERIES = List.of(
            "cpu.process",
            "cpu.system",
            "load.cores",
            "heap.used",
            "heap.committed",
            "heap.max",
            "nonheap.used",
            "nonheap.committed",
            "threads.live");

    private static final Duration HELD_REQUEST_TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    protected MeterRegistry meterRegistry;

    @Autowired
    private HeldRequestController held;

    /** Fails unless the server-request observation and the MDC follow this class's setup. */
    protected abstract void assertConventionIsActive();

    /** Fails unless the JVM and CPU binders follow this class's setup. */
    protected abstract void assertJvmMetersFollowTheConvention();

    /** A fresh {@code /persons} trace, its root stored, for a pre-assertion that reads its tags. */
    protected JsonNode personsTrace() {
        return traces.awaitTrace(traces.get("/persons"), TraceApiClient.ROOT_SPAN_EXPORTED);
    }

    /** Waits for {@code meter} to have timed a request, which a pre-assertion needs before it can say a name is absent. */
    protected void awaitTimedRequest(String meter) {
        api.get("/persons");
        await("a request timed by " + meter)
                .atMost(PeekabootApi.ASYNC_TIMEOUT)
                .until(() -> meterRegistry.find(meter).timers().stream()
                                .mapToLong(Timer::count)
                                .sum()
                        > 0);
    }

    @Test
    void aServerRequestIsClassifiedByMethodPathRouteAndStatus() {
        assertConventionIsActive();

        JsonNode summary = traceAt(OutcomeFixtureController.statusPath(200)).path("summary");

        JsonNode request = summary.path("request");
        assertThat(request.path("method").asString()).isEqualTo("GET");
        assertThat(request.path("path").asString()).isEqualTo("/fixtures/status/200");
        assertThat(request.path("route").asString()).isEqualTo("/fixtures/status/{status}");
        assertThat(request.path("statusCode").asInt()).isEqualTo(200);
        assertThat(summary.path("rootActionType").asString()).isEqualTo(RootActionType.HTTP_REQUEST.name());
    }

    @Test
    void theRootMappingKeepsItsRoute() {
        assertConventionIsActive();

        JsonNode request = traceAt("/").path("summary").path("request");

        assertThat(request.path("route").asString()).isEqualTo("/");
    }

    @Test
    void theTraceIsNamedAfterItsMethodAndRoute() {
        assertConventionIsActive();

        String name = TraceApiClient.rootOperation(traceAt(OutcomeFixtureController.statusPath(200)));

        assertThat(name).contains("/fixtures/status/{status}");
        assertThat(name.toLowerCase(Locale.ROOT)).contains("get");
    }

    /** The store files a trace in its buckets a moment after its root is stored, so a race can let this pass early but never fail it. */
    @Test
    void aClientErrorIsAWarningAndStaysOutOfErrors() {
        assertConventionIsActive();
        String traceId = traces.get(OutcomeFixtureController.statusPath(404));

        JsonNode trace = traces.awaitTrace(traceId, TraceApiClient.ROOT_SPAN_EXPORTED);

        assertClientErrorOnTheRoot(trace, 404);
        assertThat(trace.path("summary").path("outcome").asString()).isEqualTo("OK");
        assertThat(traces.listedTraceIds("bucket=errors")).doesNotContain(traceId);
    }

    /** Boot's static-resource handler maps {@code /**}, so a GET no controller matched carries that pattern as its route. */
    @Test
    void anUnmatchedPathKeepsItsPathUnderTheStaticResourceRoute() {
        assertConventionIsActive();

        JsonNode trace = traceAt("/no-such-path");

        JsonNode request = trace.path("summary").path("request");
        assertThat(request.path("statusCode").asInt()).isEqualTo(404);
        assertThat(request.path("path").asString()).isEqualTo("/no-such-path");
        assertThat(request.path("route").asString()).isEqualTo("/**");
        assertClientErrorOnTheRoot(trace, 404);
    }

    @Test
    void aServerErrorLandsInTheErrorsBucket() {
        assertConventionIsActive();
        String traceId = traces.get(OutcomeFixtureController.statusPath(500));

        traces.awaitTraceInBucket("errors", traceId);
    }

    @Test
    void anExcludedPathLeavesNoTrace() {
        assertConventionIsActive();

        traces.assertLeavesNoTrace("/actuator/health");
    }

    @Test
    void aLoggedLineIsLinkedToItsRequestsTrace() {
        assertConventionIsActive();
        String traceId = traces.get(OutcomeFixtureController.ERROR_LOG_PATH);

        JsonNode trace = traces.awaitTrace(traceId, TraceApiClient.ROOT_SPAN_EXPORTED);

        assertThat(trace.path("data")
                        .path("logs")
                        .valueStream()
                        .filter(log -> "ERROR".equals(log.path("level").asString(""))))
                .as("the fixture's ERROR line, captured against trace %s", traceId)
                .singleElement()
                .satisfies(log -> {
                    assertThat(log.path("message").asString()).isEqualTo(OutcomeFixtureController.ERROR_LOG_MESSAGE);
                    assertThat(log.path("traceId").asString()).isEqualTo(traceId);
                });
    }

    /** A rate reads above 0 only in the tick after its request, so every series is watched from before the traffic. */
    @Test
    void theHttpPanelsFillFromTheServerRequestMeter() {
        assertConventionIsActive();
        try (InsightsTicks ticks = InsightsTicks.on(port)) {
            traces.get(OutcomeFixtureController.statusPath(200));
            traces.get(OutcomeFixtureController.statusPath(404));
            traces.get(OutcomeFixtureController.statusPath(500));

            Set<String> neverAboveZero = new HashSet<>(HTTP_SERIES);
            try {
                ticks.nextMatching(tick -> {
                    neverAboveZero.removeIf(series -> isAboveZero(tick, series));
                    return neverAboveZero.isEmpty();
                });
            } catch (AssertionError noTickMatched) {
                throw new AssertionError("no tick read above 0 for " + neverAboveZero, noTickMatched);
            }
        }
    }

    @Test
    void theInFlightPanelCountsAHeldRequest() throws Exception {
        assertConventionIsActive();
        try (InsightsTicks ticks = InsightsTicks.on(port);
                HttpClient client = HttpClient.newHttpClient()) {
            CompletableFuture<HttpResponse<String>> response = client.sendAsync(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + HeldRequestController.PATH))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            try {
                held.awaitHeldTraceId(HELD_REQUEST_TIMEOUT);

                ticks.nextMatching(
                        tick -> tick.path("values").path("http-active.active").asDouble(0) >= 1);
            } finally {
                held.release();
            }
            assertThat(response.get(HELD_REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                            .statusCode())
                    .isEqualTo(200);
        }
    }

    @Test
    void theJvmAndCpuPanelsFill() {
        assertConventionIsActive();
        assertJvmMetersFollowTheConvention();
        JsonNode values;
        try (InsightsTicks ticks = InsightsTicks.on(port)) {
            values = ticks.nextMatching(tick -> JVM_AND_CPU_SERIES.stream()
                            .allMatch(series -> tick.path("values").path(series).isNumber()))
                    .path("values");
        }

        assertThat(values.path("cpu.process").asDouble()).isBetween(0.0, 100.0);
    }

    /** Ordered behind an application request whose timing has landed: the dashboard request finished first, so its timing would have landed too. */
    @Test
    void theDashboardsOwnRequestsStayOutOfTheServerRequestMeter() {
        assertConventionIsActive();
        api.get("/peekaboot/api/features");
        String meter = meterRegistry.find(MICROMETER_SERVER_METER).meters().isEmpty()
                ? OPEN_TELEMETRY_SERVER_METER
                : MICROMETER_SERVER_METER;
        long before = timedRequests(meter, "/persons");

        api.get("/persons");

        await().atMost(PeekabootApi.ASYNC_TIMEOUT).until(() -> timedRequests(meter, "/persons") > before);
        assertThat(meterRegistry.find(meter).timers())
                .extracting(ObservationConventionContract::routeOf)
                .noneMatch(route -> route != null && route.startsWith("/peekaboot"));
    }

    private JsonNode traceAt(String path) {
        return traces.awaitTrace(traces.get(path), TraceApiClient.ROOT_SPAN_EXPORTED);
    }

    private static void assertClientErrorOnTheRoot(JsonNode trace, int status) {
        assertThat(SpanTree.issuesOfType(trace, "CLIENT_ERROR")).singleElement().satisfies(issue -> {
            assertThat(issue.path("spanId").asString())
                    .isEqualTo(SpanTree.spanId(trace.path("data").path("rootSpan")));
            assertThat(issue.path("severity").asString()).isEqualTo("warning");
            assertThat(issue.path("value").asInt()).isEqualTo(status);
        });
    }

    private static boolean isAboveZero(JsonNode tick, String series) {
        JsonNode value = tick.path("values").path(series);
        return value.isNumber() && value.asDouble() > 0;
    }

    private long timedRequests(String meter, String route) {
        return meterRegistry.find(meter).timers().stream()
                .filter(timer -> route.equals(routeOf(timer)))
                .mapToLong(Timer::count)
                .sum();
    }

    /** Micrometer's convention names the route {@code uri}, OpenTelemetry's {@code http.route}. */
    private static String routeOf(Meter timer) {
        String uri = timer.getId().getTag("uri");
        return uri != null ? uri : timer.getId().getTag("http.route");
    }
}
