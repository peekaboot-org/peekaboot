package org.peekaboot.backend.insights.config;

import java.util.Map;

/** A meter a series reads when the ones before it are absent, such as the OpenTelemetry name of a Micrometer meter. */
public record MeterAlternative(String meter, Map<String, String> tags) {

    public MeterAlternative {
        tags = tags == null ? Map.of() : tags;
    }
}
