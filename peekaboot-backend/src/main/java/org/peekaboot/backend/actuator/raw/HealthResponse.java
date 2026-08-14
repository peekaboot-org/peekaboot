package org.peekaboot.backend.actuator.raw;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record HealthResponse(
    HealthBody body,
    Integer status
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HealthBody(
        String status,
        Map<String, HealthComponent> components,
        List<String> groups
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HealthComponent(
        String status,
        Map<String, Object> details
    ) {
    }
}
