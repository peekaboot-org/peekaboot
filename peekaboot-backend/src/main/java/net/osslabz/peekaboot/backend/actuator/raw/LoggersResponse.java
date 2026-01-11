package net.osslabz.peekaboot.backend.actuator.raw;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LoggersResponse(
    List<String> levels,
    Map<String, LoggerInfo> loggers,
    Map<String, GroupInfo> groups
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LoggerInfo(
        String configuredLevel,
        String effectiveLevel
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GroupInfo(
        String configuredLevel,
        List<String> members
    ) {
    }
}
