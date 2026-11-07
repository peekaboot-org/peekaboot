package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.peekaboot.fixtures.OutcomeFixtureController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

/**
 * Contract tests for the insights REST/SSE API against the real, auto-configured
 * app: config shape, the data's two views and its window, the tiles' units, and an
 * actual "tick" SSE event on the wire. ApiProblemIT covers the 400s on bad parameters.
 * The test profile's insights levels (see application-test.yml) tick every 250ms so
 * these assertions and the Playwright specs that reuse the same profile don't need to
 * wait out the 10s production default.
 */
class InsightsApiIT extends TestingAppITBase {

    @Test
    void configServesPanelsAndLevels() {
        JsonNode config = api.getJson("/peekaboot/api/insights/config");

        List<String> panelIds = new ArrayList<>();
        config.get("panels").forEach(panel -> panelIds.add(panel.get("id").asString()));
        assertThat(panelIds).contains("cpu", "heap", "http-throughput");

        assertThat(config.get("tiles")).isNotEmpty();
    }

    /** A time gauge serves milliseconds, so started-at is an epoch instant past 2001 and not after now. */
    @Test
    void theStartedAtTileServesEpochMilliseconds() {
        JsonNode tiles = api.getJson("/peekaboot/api/insights/config").get("tiles");
        JsonNode startedAt = null;
        for (JsonNode tile : tiles) {
            if ("started-at".equals(tile.get("id").asString())) {
                startedAt = tile;
            }
        }

        assertThat(startedAt).isNotNull();
        assertThat(startedAt.get("value").asDouble()).isBetween(1e12, (double) System.currentTimeMillis());
    }

    @Test
    void theFullViewServesEverySampleAndWhenItWasTaken() {
        // level 0 ticks every 250ms in the test profile, so two samples are a moment away
        JsonNode data = await().atMost(Duration.ofSeconds(5))
                .until(
                        () -> api.getJson("/peekaboot/api/insights/data?level=0&view=full"),
                        d -> d.get("count").asInt() >= 2);

        // cpu.process (process.cpu.usage) resolves in a real JVM app
        JsonNode values = data.get("series").get("cpu.process").get("values");
        int count = data.get("count").asInt();
        assertThat(values)
                .as("one value per tick the response counted, or the ring and the wire disagree")
                .hasSize(count);
        JsonNode timestamps = data.get("timestamps");
        assertThat(timestamps).hasSize(count);
        assertThat(timestamps.get(count - 1).asLong())
                .isEqualTo(data.get("endEpochMs").asLong());
        assertThat(timestamps.get(count - 1).asLong()
                        - timestamps.get(count - 2).asLong())
                .isEqualTo(data.get("intervalMs").asLong());
    }

    /** The summary view is the default: statistics per series, no samples and no timestamps. */
    @Test
    void theDefaultViewSummarisesEachSeriesWithoutItsSamples() {
        JsonNode data = await().atMost(Duration.ofSeconds(5))
                .until(
                        () -> api.getJson("/peekaboot/api/insights/data?level=0"),
                        d -> d.path("series")
                                        .path("cpu.process")
                                        .path("summary")
                                        .path("samples")
                                        .asInt()
                                >= 1);

        JsonNode cpu = data.get("series").get("cpu.process");
        assertThat(cpu.get("values").isNull()).isTrue();
        assertThat(cpu.get("stats").isNull()).isTrue();
        assertThat(data.get("timestamps").isNull()).isTrue();
        assertThat(cpu.get("summary").get("max").isNumber())
                .as("a summary over a sample holds its statistics")
                .isTrue();
        assertThat(cpu.get("summary").get("max").asDouble())
                .as("a CPU statistic lies from 0 to 100")
                .isBetween(0.0, 100.0);
        assertThat(data.get("problems").isArray()).isTrue();
    }

    @Test
    void seriesAndSinceNarrowTheResponse() {
        JsonNode narrowed = api.getJson("/peekaboot/api/insights/data?level=0&series=cpu.process,heap.used");
        JsonNode future = api.getJson(
                "/peekaboot/api/insights/data?level=0&since={since}",
                Instant.now().plus(Duration.ofDays(1)));

        assertThat(narrowed.get("series").propertyNames()).containsExactly("cpu.process", "heap.used");
        assertThat(future.get("count").asInt()).isZero();
        assertThat(future.get("series")
                        .get("cpu.process")
                        .get("summary")
                        .get("samples")
                        .asInt())
                .isZero();
    }

    /**
     * A problem over real HTTP: rule, severity, series, threshold and unit as words a client reads.
     * The fixture's ERROR line makes sure the window holds one; {@code InsightsProblemsTest} pins the
     * counting, which other classes' lines would blur here.
     */
    @Test
    void aProblemIsServedInTheShapeOfAnIssue() {
        traces.get(OutcomeFixtureController.ERROR_LOG_PATH);

        JsonNode problem = await().atMost(Duration.ofSeconds(10))
                .until(
                        () -> errorLogsProblem(api.getJson("/peekaboot/api/insights/data?level=0")),
                        found -> !found.isMissingNode());

        assertThat(problem.get("severity").asString()).isEqualTo("warning");
        assertThat(problem.get("series").get(0).asString()).isEqualTo("log-events.errors");
        assertThat(problem.get("value").asDouble()).isGreaterThanOrEqualTo(1.0);
        assertThat(problem.get("threshold").asDouble()).isEqualTo(1.0);
        assertThat(problem.get("unit").asString()).isEqualTo("count");
    }

    @Test
    void streamDeliversTickEventWithinFiveSeconds() throws Exception {
        // Closed explicitly (not just the response stream) so the client-side socket
        // drops right away. The server only notices the disconnect on its next
        // heartbeat to this subscriber (InsightsSsePublisher, 15s); context shutdown
        // completes the emitter regardless, so Tomcat is not held at JVM exit.
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/peekaboot/api/insights/stream"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "text/event-stream")
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

            assertThat(response.statusCode()).isEqualTo(200);

            // The JDK client has no read timeout, so a stream that stays open but silent
            // would block readLine() indefinitely; the read runs off-thread and the wait
            // is bounded here. Closing the body on the way out unblocks the reader.
            try (InputStream body = response.body()) {
                CompletableFuture<Boolean> sawTick = CompletableFuture.supplyAsync(() -> containsTickEvent(body));
                assertThat(sawTick.get(5, TimeUnit.SECONDS))
                        .as("received a tick SSE event")
                        .isTrue();
            } catch (TimeoutException silentStream) {
                fail("no tick SSE event within 5 seconds");
            }
        }
    }

    private static boolean containsTickEvent(InputStream body) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event:") && line.contains("tick")) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode errorLogsProblem(JsonNode data) {
        for (JsonNode problem : data.get("problems")) {
            if ("ERROR_LOGS".equals(problem.get("rule").asString())) {
                return problem;
            }
        }
        return MissingNode.getInstance();
    }
}
