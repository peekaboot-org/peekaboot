package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.peekaboot.fixtures.OutcomeFixtureController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

/**
 * Contract tests for the insights REST/SSE API against the real, auto-configured
 * app: config shape, the data's two views and its window, the tiles' units, and an
 * actual "tick" SSE event on the wire. ApiProblemIT covers the 400s on bad parameters.
 * A test that needs a sample waits for the stream's own tick ({@link InsightsTicks}): level 0
 * ticks every 250ms in the test profile (see application-test.yml), so one is a moment away.
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
        try (InsightsTicks ticks = InsightsTicks.on(port)) {
            ticks.next();
            ticks.next();
        }
        JsonNode data = api.getJson("/peekaboot/api/insights/data?level=0&view=full");

        // cpu.process (process.cpu.usage) resolves in a real JVM app
        JsonNode values = data.get("series").get("cpu.process").get("values");
        int count = data.get("count").asInt();
        assertThat(count).isGreaterThanOrEqualTo(2);
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
        try (InsightsTicks ticks = InsightsTicks.on(port)) {
            ticks.nextMatching(tick -> tick.path("values").path("cpu.process").isNumber());
        }
        JsonNode data = api.getJson("/peekaboot/api/insights/data?level=0");

        JsonNode cpu = data.get("series").get("cpu.process");
        assertThat(cpu.get("summary").get("samples").asInt()).isPositive();
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
        // the series is a rate, so only a subscriber that was there before the line sees its tick
        try (InsightsTicks ticks = InsightsTicks.on(port)) {
            traces.get(OutcomeFixtureController.ERROR_LOG_PATH);
            ticks.nextMatching(
                    tick -> tick.path("values").path("log-events.errors").asDouble() > 0);
        }
        JsonNode problem = errorLogsProblem(api.getJson("/peekaboot/api/insights/data?level=0"));

        assertThat(problem.isMissingNode()).isFalse();
        assertThat(problem.get("severity").asString()).isEqualTo("warning");
        assertThat(problem.get("series").get(0).asString()).isEqualTo("log-events.errors");
        assertThat(problem.get("value").asDouble()).isGreaterThanOrEqualTo(1.0);
        assertThat(problem.get("threshold").asDouble()).isEqualTo(1.0);
        assertThat(problem.get("unit").asString()).isEqualTo("count");
    }

    @Test
    void theStreamDeliversEachTickWithItsValues() {
        try (InsightsTicks ticks = InsightsTicks.on(port)) {
            JsonNode tick = ticks.next();
            assertThat(tick.path("epochMs").isNumber()).isTrue();
            assertThat(tick.path("values").has("cpu.process")).isTrue();
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
