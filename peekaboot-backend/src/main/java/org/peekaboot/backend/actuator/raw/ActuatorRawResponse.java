package org.peekaboot.backend.actuator.raw;

import java.util.Map;

/**
 * Top-level container for the raw actuator response with spring.actuator namespace.
 */
public record ActuatorRawResponse(
    Spring spring
) {
    public record Spring(Map<String, Object> actuator) {}

    public static ActuatorRawResponse wrap(Map<String, Object> actuatorData) {
        return new ActuatorRawResponse(new Spring(actuatorData));
    }
}
