package org.peekaboot.backend.insights.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.insights.AggregateStats;
import tools.jackson.databind.ObjectMapper;

class InsightsEventJsonTest {

    private final InsightsEventJson eventJson = new InsightsEventJson(new ObjectMapper());

    @Test
    void tickPayloadMapsNaNToNull() {
        Map<String, Double> values = new LinkedHashMap<>();
        values.put("a", 1.5);
        values.put("b", Double.NaN);
        String json = eventJson.tick(7_000, values);
        assertThat(json).isEqualTo("{\"epochMs\":7000,\"values\":{\"a\":1.5,\"b\":null}}");
    }

    /** The seven statistics the dashboard charts, by name; the sample count stays server-side. */
    @Test
    void rollupPayloadCarriesTheSevenStatsAndNotTheSampleCount() {
        var entry = AggregateStats.of(new double[] {2.0});
        String json = eventJson.rollUp(1, 60_000, Map.of("a", entry));
        assertThat(json)
                .isEqualTo("{\"level\":1,\"epochMs\":60000,\"entries\":{\"a\":"
                        + "{\"min\":2.0,\"max\":2.0,\"avg\":2.0,\"median\":2.0,\"p90\":2.0,\"p95\":2.0,\"p99\":2.0}}}");
    }
}
