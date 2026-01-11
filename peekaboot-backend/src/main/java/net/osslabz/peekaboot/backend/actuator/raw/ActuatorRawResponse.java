package net.osslabz.peekaboot.backend.actuator.raw;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Top-level container for the raw actuator response from PeekabookActuatorService.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActuatorRawResponse(
    SpringInfo spring,
    HealthResponse health,
    InfoResponse info,
    EnvResponse env,
    LoggersResponse loggers,
    FlywayResponse flyway,
    ConfigPropsResponse configprops
) {
}
