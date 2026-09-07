package org.peekaboot.backend.insights.config;

import java.util.Map;

/**
 * One data series drawn within a panel.
 *
 * @param stat          one of {@code value|rate|avg|max}; defaults to {@code value} when null
 * @param unit          optionally overrides the panel unit ({@code bytes|percent|millis|count|persec|bytes-persec})
 * @param subtractMeter rejected at load unless {@code stat} is {@code value}. For {@code avg}/{@code max}
 *                       this is a hard limit: an average or a maximum of a difference cannot be
 *                       recovered from two independent aggregates. For {@code rate} it is a design
 *                       decision, not an impossibility - rate(A) minus rate(B) is well defined, but
 *                       subtracting the underlying cumulative counts first would trip the negative-delta
 *                       reset guard whenever the subtracted meter grows faster than the primary one
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
