package org.peekaboot.backend.actuator.parsed;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses the raw Map response from PeekabootActuatorService into typed actuator beans.
 * An endpoint the service could not invoke is absent from that map and parses as null;
 * the others are unaffected.
 */
@Component
public class ActuatorResponseParser {

    private final ObjectMapper objectMapper;

    public ActuatorResponseParser() {
        this.objectMapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    public ActuatorParsedData parse(Map<String, Object> rawData) {
        if (rawData == null) {
            return new ActuatorParsedData(null, null, null, null, null, null, null, null);
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        rawData.forEach((key, value) -> {
            if (value != null) {
                sanitized.put(key, value);
            }
        });
        return objectMapper.convertValue(sanitized, ActuatorParsedData.class);
    }
}
