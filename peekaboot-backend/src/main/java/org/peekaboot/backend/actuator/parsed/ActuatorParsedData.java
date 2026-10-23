package org.peekaboot.backend.actuator.parsed;

/**
 * The typed actuator sections. Each constant below is the {@code InsightsSource} id its
 * section is read under; a source registered under any other id is never parsed.
 */
public record ActuatorParsedData(
        SpringInfo spring,
        HealthResponse health,
        InfoResponse info,
        EnvResponse env,
        LoggersResponse loggers,
        FlywayResponse flyway,
        LiquibaseResponse liquibase,
        ConfigPropsResponse configprops,
        ScheduledTasksResponse scheduledtasks) {

    public static final String SPRING = "spring";
    public static final String HEALTH = "health";
    public static final String INFO = "info";
    public static final String ENV = "env";
    public static final String LOGGERS = "loggers";
    public static final String FLYWAY = "flyway";
    public static final String LIQUIBASE = "liquibase";
    public static final String CONFIGPROPS = "configprops";
    public static final String SCHEDULEDTASKS = "scheduledtasks";
}
