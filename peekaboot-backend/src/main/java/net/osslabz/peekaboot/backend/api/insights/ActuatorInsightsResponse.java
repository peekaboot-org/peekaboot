package net.osslabz.peekaboot.backend.api.insights;

import net.osslabz.peekaboot.backend.domain.application.ApplicationInfo;
import net.osslabz.peekaboot.backend.domain.config.ConfigInfo;
import net.osslabz.peekaboot.backend.domain.datasource.DataSourceInfo;
import net.osslabz.peekaboot.backend.domain.environment.EnvironmentInfo;
import net.osslabz.peekaboot.backend.domain.flyway.FlywayInfo;
import net.osslabz.peekaboot.backend.domain.health.HealthInfo;
import net.osslabz.peekaboot.backend.domain.loggers.LoggersInfo;
import net.osslabz.peekaboot.backend.domain.runtime.RuntimeInfo;

import java.util.List;

public record ActuatorInsightsResponse(
    ApplicationInfo application,
    RuntimeInfo runtime,
    List<DataSourceInfo> dataSources,
    HealthInfo health,
    EnvironmentInfo environment,
    LoggersInfo loggers,
    FlywayInfo flyway,
    ConfigInfo config
) {}
