package org.peekaboot.backend.actuator.parsed;

import java.util.Map;

/**
 * The descriptor {@code HealthEndpoint.health()} returns: the aggregate {@code status} at
 * the top and one entry per health contributor under {@code components}. Absent
 * collections bind as empty (see {@link ActuatorResponseParser}).
 */
public record HealthResponse(String status, Map<String, HealthComponent> components) {

    public HealthResponse {
        components = Absent.orEmpty(components);
    }

    /**
     * One contributor: an indicator carries {@code details}, a composite (Spring's
     * {@code db} with two DataSources, or any custom composite) carries its children under
     * {@code components} instead.
     */
    public record HealthComponent(String status, Map<String, Object> details, Map<String, HealthComponent> components) {

        public HealthComponent {
            details = Absent.orEmpty(details);
            components = Absent.orEmpty(components);
        }

        /** An indicator: details and no children. */
        public HealthComponent(String status, Map<String, Object> details) {
            this(status, details, null);
        }
    }
}
