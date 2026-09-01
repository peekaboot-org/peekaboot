package org.peekaboot.backend.actuator.parsed;

/**
 * Typed container for parsed actuator data used by insights mappers.
 */
public record ActuatorParsedData(
        SpringInfo spring,
        HealthResponse health,
        InfoResponse info,
        EnvResponse env,
        LoggersResponse loggers,
        FlywayResponse flyway,
        ConfigPropsResponse configprops,
        ScheduledTasksResponse scheduledtasks) {}
