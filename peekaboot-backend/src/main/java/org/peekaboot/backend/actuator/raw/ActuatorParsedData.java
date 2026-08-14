package org.peekaboot.backend.actuator.raw;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Typed container for parsed actuator data used by insights mappers.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActuatorParsedData(
    SpringInfo spring,
    HealthResponse health,
    InfoResponse info,
    EnvResponse env,
    LoggersResponse loggers,
    FlywayResponse flyway,
    ConfigPropsResponse configprops,
    ScheduledTasksResponse scheduledtasks
) {
}
