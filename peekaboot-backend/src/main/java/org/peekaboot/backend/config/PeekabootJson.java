package org.peekaboot.backend.config;

import tools.jackson.databind.json.JsonMapper;

/**
 * The mapper for everything Peekaboot puts on the wire - its REST responses and the
 * insights SSE events. Deliberately not the application's Jackson bean: the dashboard
 * reads camelCase names, tests some fields with {@code !== null} and parses every instant
 * as an ISO-8601 string, and an application that configures {@code spring.jackson.*}
 * differently (a naming strategy, {@code non_null} inclusion, timestamps) must not change
 * that. Jackson's own defaults are exactly that shape, so this is the plain default mapper.
 */
public final class PeekabootJson {

    public static final JsonMapper MAPPER = JsonMapper.builder().build();

    /**
     * JSON has no NaN, so every value Peekaboot puts on the wire maps NaN to null
     * through this one rule; a null in stays a null out.
     */
    public static Double nanToNull(Double value) {
        return value == null || Double.isNaN(value) ? null : value;
    }

    private PeekabootJson() {}
}
