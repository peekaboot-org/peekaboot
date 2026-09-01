package org.peekaboot.backend.actuator.parsed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LoggersResponse(Map<String, LoggerInfo> loggers) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LoggerInfo(String configuredLevel, String effectiveLevel) {}
}
