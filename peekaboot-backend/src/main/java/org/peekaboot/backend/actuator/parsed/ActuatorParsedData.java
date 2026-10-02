package org.peekaboot.backend.actuator.parsed;

public record ActuatorParsedData(
        SpringInfo spring,
        HealthResponse health,
        InfoResponse info,
        EnvResponse env,
        LoggersResponse loggers,
        FlywayResponse flyway,
        LiquibaseResponse liquibase,
        ConfigPropsResponse configprops,
        ScheduledTasksResponse scheduledtasks) {}
