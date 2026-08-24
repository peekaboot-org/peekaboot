package org.peekaboot.backend.actuator.parsed;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses the raw Map response from PeekabootActuatorService into typed actuator beans.
 * Entries that are not JSON objects (e.g. the "Error: ..." placeholders stored
 * for endpoints that failed to invoke) are treated as absent, so one broken
 * endpoint never breaks parsing of the others.
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
            if (isConvertible(value)) {
                sanitized.put(key, value);
            }
        });
        return objectMapper.convertValue(sanitized, ActuatorParsedData.class);
    }

    /**
     * Endpoint results are Maps (from JSON) or POJOs (WebEndpointResponse,
     * descriptor objects at runtime) - both convert; only the String error
     * placeholders don't.
     */
    private static boolean isConvertible(Object value) {
        return value != null && !(value instanceof String);
    }
}
