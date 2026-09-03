package org.peekaboot.backend.insights.config;

import java.util.Map;

/**
 * A single-value dashboard tile.
 *
 * @param format one of {@code duration|datetime|bytes|count}
 * @param live   {@code null} means false (static, sampled until first non-NaN then frozen)
 */
public record TileDef(String id, String label, String meter, Map<String, String> tags, String format, Boolean live) {

    public TileDef {
        tags = tags == null ? Map.of() : tags;
    }
}
