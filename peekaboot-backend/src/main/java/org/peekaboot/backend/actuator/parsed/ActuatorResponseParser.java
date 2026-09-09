package org.peekaboot.backend.actuator.parsed;

import java.util.Map;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses the raw Map response from PeekabootActuatorService into typed actuator beans.
 * An endpoint the service could not invoke is absent from that map and parses as null;
 * the others are unaffected. Inside a section, a collection the response left out binds as
 * empty, normalised once by the records' compact constructors so no mapper has to guard for
 * it. Properties the records do not declare are ignored here, for every record at once: the
 * records bind only what the mappers read, and a Boot release adding a field must not break
 * the parse.
 */
public class ActuatorResponseParser {

    private final ObjectMapper objectMapper;

    public ActuatorResponseParser() {
        this.objectMapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    public ActuatorParsedData parse(Map<String, Object> rawData) {
        return objectMapper.convertValue(rawData, ActuatorParsedData.class);
    }
}
