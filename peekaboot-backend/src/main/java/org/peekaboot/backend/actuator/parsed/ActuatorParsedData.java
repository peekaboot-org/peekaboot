package org.peekaboot.backend.actuator.parsed;

public record ActuatorParsedData(
        SpringInfo spring,
        HealthResponse health,
        InfoResponse info,
        EnvResponse env,
        LoggersResponse loggers,
        FlywayResponse flyway,
        ConfigPropsResponse configprops,
        ScheduledTasksResponse scheduledtasks) {}
