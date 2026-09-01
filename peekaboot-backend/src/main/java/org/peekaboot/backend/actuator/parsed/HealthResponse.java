package org.peekaboot.backend.actuator.parsed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * The descriptor {@code HealthEndpoint.health()} returns: the aggregate {@code status} at
 * the top and one entry per health contributor under {@code components}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HealthResponse(String status, Map<String, HealthComponent> components, List<String> groups) {

    /**
     * One contributor: an indicator carries {@code details}, a composite (Spring's
     * {@code db} with two DataSources, or any custom composite) carries its children under
     * {@code components} instead.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HealthComponent(String status, Map<String, Object> details, Map<String, HealthComponent> components) {

        /** An indicator: details and no children. */
        public HealthComponent(String status, Map<String, Object> details) {
            this(status, details, null);
        }
    }
}
