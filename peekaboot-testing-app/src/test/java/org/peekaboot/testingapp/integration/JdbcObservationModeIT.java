package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.tracing.event.SpanData;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.ClassUtils;
import tools.jackson.databind.JsonNode;

/**
 * What the Queries tab and panel show in the JDBC observation mode of this run: OpenTelemetry's,
 * which the starter ships, or datasource-micrometer's plain one, which a build that excludes
 * {@code datasource-micrometer-opentelemetry} gets. The plain-jdbc run sets
 * {@code peekaboot.it.jdbc-mode=plain}.
 */
class JdbcObservationModeIT extends TestingAppITBase {

    private static final String JDBC_MODE = System.getProperty("peekaboot.it.jdbc-mode", "open-telemetry");

    private static final boolean PLAIN = "plain".equals(JDBC_MODE);

    private static final String OPEN_TELEMETRY_CONVENTION =
            "net.ttddyy.observation.tracing.opentelemetry.OpenTelemetryQueryObservationConvention";

    private static final String OPEN_TELEMETRY_STATEMENT_TAG = "db.query.text";

    private static final String PLAIN_STATEMENT_TAG = "jdbc.query[0]";

    @Autowired
    private TraceStore traceStore;

    @Test
    void theQuerySpansFollowTheModeOfThisRun() {
        assertThat(ClassUtils.isPresent(OPEN_TELEMETRY_CONVENTION, null))
                .as("the OpenTelemetry JDBC module on the classpath of a %s run", JDBC_MODE)
                .isEqualTo(!PLAIN);

        String traceId = traces.get("/persons");
        JsonNode trace = traces.awaitTrace(traceId, TraceApiClient.ROOT_SPAN_EXPORTED);

        JsonNode query = queriesOf(trace).getFirst();
        Map<String, String> tags = storedTags(traceId, query);
        if (PLAIN) {
            assertThat(tags).containsKey(PLAIN_STATEMENT_TAG).doesNotContainKey(OPEN_TELEMETRY_STATEMENT_TAG);
        } else {
            assertThat(tags).containsKey(OPEN_TELEMETRY_STATEMENT_TAG);
        }
    }

    /** A rate reads above 0 only in the tick after its request, so the stream is watched from before it. */
    @Test
    void theQueriesPanelFills() {
        try (InsightsTicks ticks = InsightsTicks.on(port)) {
            traces.get("/persons");

            JsonNode values = ticks.nextMatching(tick ->
                            tick.path("values").path("jdbc-queries.rate").asDouble(0) > 0)
                    .path("values");

            assertThat(values.path("jdbc-queries.avg").isNumber())
                    .as("jdbc-queries.avg in %s", values)
                    .isTrue();
            assertThat(values.path("jdbc-queries.avg").asDouble()).isFinite();
        }
    }

    /**
     * Peekaboot names the product from the connection's JDBC URL in either mode. Without that,
     * datasource-micrometer's OpenTelemetry module calls H2 {@code other_sql} and plain mode
     * falls back to the pool's name or the connection's catalog.
     */
    @Test
    void aQueryOnH2IsLabelledH2InEitherMode() {
        String traceId = traces.get("/persons");
        JsonNode trace = traces.awaitTrace(traceId, TraceApiClient.ROOT_SPAN_EXPORTED);

        assertThat(queriesOf(trace))
                .allSatisfy(
                        query -> assertThat(query.path("dbSystem").asString("")).isEqualTo("H2"));
    }

    private static List<JsonNode> queriesOf(JsonNode trace) {
        List<JsonNode> queries =
                trace.path("data").path("queries").valueStream().toList();
        assertThat(queries)
                .as("queries of %s", trace.path("summary").path("traceId"))
                .isNotEmpty();
        return queries;
    }

    /** The trace API serves a query's statement tags as its statement, so the mode is read off the stored span. */
    private Map<String, String> storedTags(String traceId, JsonNode query) {
        String spanId = query.path("spanId").asString();
        SpanData span = traceStore.getTrace(traceId).orElseThrow().snapshot().spans().stream()
                .filter(stored -> stored.spanId().equals(spanId))
                .findFirst()
                .orElseThrow();
        return span.tags() == null ? Map.of() : span.tags();
    }
}
