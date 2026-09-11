package org.peekaboot.backend.actuator.parsed;

import java.util.Map;

/** Absent collections bind as empty (see {@link ActuatorResponseParser}). */
public record LoggersResponse(Map<String, LoggerInfo> loggers) {

    public LoggersResponse {
        loggers = Absent.orEmpty(loggers);
    }

    public record LoggerInfo(String configuredLevel, String effectiveLevel) {}
}
