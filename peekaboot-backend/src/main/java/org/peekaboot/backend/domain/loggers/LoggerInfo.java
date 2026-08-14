package org.peekaboot.backend.domain.loggers;

public record LoggerInfo(
    String name,
    String configuredLevel,
    String effectiveLevel
) {
    public boolean isConfigured() {
        return configuredLevel != null;
    }
}
