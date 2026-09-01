package org.peekaboot.backend.actuator.parsed;

import java.util.Map;

public record LoggersResponse(Map<String, LoggerInfo> loggers) {

    public record LoggerInfo(String configuredLevel, String effectiveLevel) {}
}
