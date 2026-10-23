package org.peekaboot.backend.actuator.parsed;

import java.util.Map;

/** Absent collections bind as empty (see {@link ActuatorResponseParser}). */
public record LoggersResponse(Map<String, LoggerLevelsDescriptor> loggers) {

    public LoggersResponse {
        loggers = Absent.orEmpty(loggers);
    }

    public record LoggerLevelsDescriptor(String configuredLevel, String effectiveLevel) {}
}
