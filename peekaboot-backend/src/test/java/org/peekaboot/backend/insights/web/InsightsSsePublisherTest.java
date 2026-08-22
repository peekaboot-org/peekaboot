package org.peekaboot.backend.insights.web;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InsightsSsePublisherTest {

    private final InsightsSsePublisher publisher = new InsightsSsePublisher(new ObjectMapper());

    @Test
    void tracksSubscribers() {
        assertThat(publisher.subscriberCount()).isZero();
        var emitter = publisher.subscribe();
        assertThat(publisher.subscriberCount()).isEqualTo(1);
        emitter.complete();
        // completion callback runs synchronously for SseEmitter.complete()
        assertThat(publisher.subscriberCount()).isZero();
    }

    @Test
    void tickPayloadMapsNaNToNull() {
        Map<String, Double> values = new LinkedHashMap<>();
        values.put("a", 1.5);
        values.put("b", Double.NaN);
        String json = publisher.tickJson(7_000, values, Map.of());
        assertThat(json).isEqualTo("{\"epochMs\":7000,\"values\":{\"a\":1.5,\"b\":null},\"tiles\":{}}");
    }

    @Test
    void rollupPayloadCarriesAllStats() {
        var entry = org.peekaboot.backend.insights.AggregateStats.of(new double[]{2.0});
        String json = publisher.rollupJson(1, 60_000, Map.of("a", entry));
        assertThat(json).contains("\"level\":1").contains("\"avg\":2.0").contains("\"p99\":2.0");
    }
}
