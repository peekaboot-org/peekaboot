package org.peekaboot.backend.insights.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.peekaboot.backend.config.PeekabootJson;
import org.peekaboot.backend.insights.AggregateStats;
import tools.jackson.databind.ObjectMapper;

/**
 * Renders one collector event as the JSON document the insights stream carries, which
 * {@code dashboard/tabs/insights-store.js} mirrors field by field. NaN is written as null:
 * JSON has no NaN, and a missing value is what the charts draw a gap for.
 */
final class InsightsEventJson {

    private final ObjectMapper objectMapper;

    InsightsEventJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String tick(long epochMs, Map<String, Double> values) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("epochMs", epochMs);
        payload.put("values", nullSafeMap(values));
        return objectMapper.writeValueAsString(payload);
    }

    String rollUp(int level, long epochMs, Map<String, AggregateStats> entries) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("level", level);
        payload.put("epochMs", epochMs);

        Map<String, Object> entryPayloads = new LinkedHashMap<>();
        for (Map.Entry<String, AggregateStats> entry : entries.entrySet()) {
            entryPayloads.put(entry.getKey(), nullSafeMap(entry.getValue().byName()));
        }
        payload.put("entries", entryPayloads);

        return objectMapper.writeValueAsString(payload);
    }

    private static Map<String, Object> nullSafeMap(Map<String, Double> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            result.put(entry.getKey(), PeekabootJson.nanToNull(entry.getValue()));
        }
        return result;
    }
}
