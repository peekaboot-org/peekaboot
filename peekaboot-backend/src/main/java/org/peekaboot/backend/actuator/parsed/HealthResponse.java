package org.peekaboot.backend.actuator.parsed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * The descriptor {@code HealthEndpoint.health()} returns: the aggregate {@code status} at
 * the top and one entry per health indicator under {@code components}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HealthResponse(String status, Map<String, HealthComponent> components, List<String> groups) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HealthComponent(String status, Map<String, Object> details) {}
}
