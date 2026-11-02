package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.peekaboot.fixtures.QueryFixtureController;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import tools.jackson.databind.JsonNode;

/**
 * {@code GET /api/routes} over real HTTP. The All bucket also holds every other test's traces, so
 * each assertion reads this test's own route row, or bounds the window by that row's requests.
 */
class RouteStatsIT extends TestingAppITBase {

    private static final String ROUTE = "/fixtures/routes/{status}";

    /** Waits for the root, since only a complete request counts on a route. */
    private String request(int status, long pauseMs) {
        return traces.getAndAwaitComplete(QueryFixtureController.routePath(status, pauseMs));
    }

    private String request(int status) {
        return request(status, 0);
    }

    private JsonNode summaryOf(String traceId) {
        return traces.awaitTrace(traceId, TraceApiClient.COMPLETE).path("summary");
    }

    private Instant startOf(String traceId) {
        return Instant.parse(summaryOf(traceId).path("startTime").asString());
    }

    private List<String> listedOnTheRoute() {
        return api.getJson("/peekaboot/api/traces?route={route}", ROUTE)
                .path("traces")
                .valueStream()
                .map(TraceApiClient::traceId)
                .toList();
    }

    private static JsonNode rowOf(JsonNode routes, String method, String route) {
        return routes.path("routes")
                .valueStream()
                .filter(row -> method.equals(row.path("method").asString(""))
                        && route.equals(row.path("route").asString("")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + method + " " + route + " in " + routes));
    }

    /**
     * One test sends every request this route sees, in an order that tells the newest failure from
     * the first. The slow request goes last, so no request newer than it can also run slow.
     */
    @Test
    void aggregatesTheRoutesRequests() {
        String firstError = request(503);
        String firstNotFound = request(404);
        String ok = request(200);
        String latestError = request(503);
        String secondNotFound = request(404);
        String thirdNotFound = request(404);
        String slow = request(200, 1_200);
        List<String> sent = List.of(firstError, firstNotFound, ok, latestError, secondNotFound, thirdNotFound, slow);

        JsonNode routes = api.getJson("/peekaboot/api/routes");
        JsonNode row = rowOf(routes, "GET", ROUTE);
        long[] durationsMs = sent.stream()
                .mapToLong(traceId -> summaryOf(traceId).path("durationMs").asLong())
                .sorted()
                .toArray();

        assertThat(listedOnTheRoute())
                .as("the route lists this test's requests alone")
                .hasSameElementsAs(sent);
        assertThat(row.path("count").asInt()).isEqualTo(7);
        assertThat(row.path("errorCount").asInt()).isEqualTo(2);
        assertThat(row.path("latestErrorTraceId").asString())
                .isEqualTo(latestError)
                .isNotEqualTo(firstError);
        assertThat(row.path("latestSlowTraceId").asString()).isEqualTo(slow);
        assertThat(row.path("maxMs").asLong()).isGreaterThanOrEqualTo(1_200).isEqualTo(durationsMs[6]);
        assertThat(row.path("p95Ms").asLong())
                .as("nearest rank over seven requests is the slowest one")
                .isEqualTo(durationsMs[6]);
        assertThat(row.path("p50Ms").asLong())
                .as("nearest rank picks the fourth of seven")
                .isEqualTo(durationsMs[3]);
        assertThat(row.path("issueCounts").path("CLIENT_ERROR").asInt()).isEqualTo(3);
        assertThat(row.path("issueCounts").path("REPEATED_QUERY").asInt()).isZero();
        JsonNode window = routes.path("window");
        assertThat(window.path("traceCount").asInt())
                .isGreaterThanOrEqualTo(row.path("count").asInt());
        assertThat(Instant.parse(window.path("from").asString())).isBeforeOrEqualTo(startOf(firstError));
        assertThat(Instant.parse(window.path("to").asString())).isAfterOrEqualTo(startOf(slow));
    }

    /**
     * Boot's static-resource handler maps {@code /**}, so a GET no controller matched carries that
     * pattern. Other tests' unmatched requests share the row, so its count is a lower bound.
     */
    @Test
    void listsAGetNoControllerMatchedUnderTheStaticResourcePattern() {
        String lost = traces.getAndAwaitComplete("/fixtures/unmapped/" + UUID.randomUUID());

        String route = summaryOf(lost).path("request").path("route").asString();
        JsonNode routes = api.getJson("/peekaboot/api/routes");

        assertThat(route).isEqualTo("/**");
        assertThat(rowOf(routes, "GET", route).path("count").asInt()).isPositive();
    }

    /** The static-resource handler takes a POST no controller matched as well, and answers it with a 404. */
    @Test
    void listsAPostNoControllerMatchedUnderTheStaticResourcePattern() {
        ResponseEntity<String> response =
                api.postForm("/fixtures/unmapped/" + UUID.randomUUID(), new LinkedMultiValueMap<>());

        JsonNode request =
                summaryOf(TraceApiClient.traceIdOf(response.getHeaders())).path("request");
        JsonNode routes = api.getJson("/peekaboot/api/routes");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(request.path("method").asString()).isEqualTo("POST");
        assertThat(request.path("route").asString()).isEqualTo("/**");
        assertThat(request.path("statusCode").asInt()).isEqualTo(404);
        assertThat(rowOf(routes, "POST", "/**").path("count").asInt()).isPositive();
    }
}
