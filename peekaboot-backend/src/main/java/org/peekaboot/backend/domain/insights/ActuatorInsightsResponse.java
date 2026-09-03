package org.peekaboot.backend.domain.insights;

import java.util.List;
import org.peekaboot.backend.domain.application.ApplicationInfo;
import org.peekaboot.backend.domain.config.ConfigInfo;
import org.peekaboot.backend.domain.datasource.DataSourceInfo;
import org.peekaboot.backend.domain.environment.EnvironmentInfo;
import org.peekaboot.backend.domain.flyway.FlywayInfo;
import org.peekaboot.backend.domain.health.HealthInfo;
import org.peekaboot.backend.domain.loggers.LoggersInfo;
import org.peekaboot.backend.domain.runtime.RuntimeInfo;
import org.peekaboot.backend.domain.scheduledtasks.ScheduledTasksInfo;
import org.peekaboot.backend.domain.server.ServerInfo;

public record ActuatorInsightsResponse(
        ApplicationInfo application,
        RuntimeInfo runtime,
        List<DataSourceInfo> dataSources,
        HealthInfo health,
        EnvironmentInfo environment,
        LoggersInfo loggers,
        FlywayInfo flyway,
        ConfigInfo config,
        ScheduledTasksInfo scheduledTasks,
        ServerInfo server) {}
