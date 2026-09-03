package org.peekaboot.backend.insights.config;

import java.util.Map;

/**
 * One data series drawn within a panel.
 *
 * @param stat          one of {@code value|rate|avg|max}; defaults to {@code value} when null
 * @param unit          optionally overrides the panel unit ({@code bytes|percent|millis|count|persec|bytes-persec})
 * @param subtractMeter only meaningful when {@code stat} is {@code value}
 */
public record SeriesDef(
        String id,
        String label,
        String meter,
        Map<String, String> tags,
        String stat,
        String subtractMeter,
        String unit) {

    public SeriesDef {
        tags = tags == null ? Map.of() : tags;
    }
}
