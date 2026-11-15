package org.peekaboot.backend.mapper.actuator;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.actuator.parsed.ActuatorParsedData;
import org.peekaboot.backend.datasource.DataSourceMetadata;
import org.peekaboot.backend.domain.actuator.ActuatorInsightsResponse;
import org.peekaboot.backend.domain.server.ServerInfo;
import org.peekaboot.backend.masking.MaskingEngine;
import org.springframework.boot.info.GitProperties;

/** Maps one reading of the actuator sections to the insights response, each section by its own mapper. */
public final class ActuatorInsightsMapper {

    private final HealthMapper healthMapper;
    private final RuntimeMapper runtimeMapper;
    private final DataSourceMapper dataSourceMapper;
    private final ApplicationMapper applicationMapper;
    private final EnvironmentMapper environmentMapper;
    private final LoggersMapper loggersMapper;
    private final FlywayMapper flywayMapper;
    private final LiquibaseMapper liquibaseMapper;
    private final ConfigMapper configMapper;
    private final ScheduledTasksMapper scheduledTasksMapper;

    public ActuatorInsightsMapper(MaskingEngine maskingEngine) {
        this.healthMapper = new HealthMapper(maskingEngine);
        this.runtimeMapper = new RuntimeMapper();
        this.dataSourceMapper = new DataSourceMapper(maskingEngine);
        this.applicationMapper = new ApplicationMapper(maskingEngine);
        this.environmentMapper = new EnvironmentMapper(maskingEngine);
        this.loggersMapper = new LoggersMapper();
        this.flywayMapper = new FlywayMapper();
        this.liquibaseMapper = new LiquibaseMapper();
        this.configMapper = new ConfigMapper(maskingEngine);
        this.scheduledTasksMapper = new ScheduledTasksMapper(maskingEngine);
    }

    /**
     * {@code unmask} is the caller's already-resolved decision, threaded to every mapper
     * here that carries property values, so none of them re-derives it.
     *
     * @param git null where git stays off the Overview
     */
    public ActuatorInsightsResponse map(
            ActuatorParsedData typed,
            List<DataSourceMetadata> dataSources,
            @Nullable GitProperties git,
            Locale locale,
            boolean unmask) {
        return new ActuatorInsightsResponse(
                applicationMapper.map(typed.info(), git, typed.spring(), unmask),
                runtimeMapper.map(typed.info(), typed.health()),
                dataSourceMapper.map(dataSources, typed.health(), unmask),
                healthMapper.map(typed.health(), unmask),
                environmentMapper.map(typed.env(), unmask),
                loggersMapper.map(typed.loggers()),
                flywayMapper.map(typed.flyway()),
                liquibaseMapper.map(typed.liquibase()),
                configMapper.map(typed.configprops(), unmask),
                scheduledTasksMapper.map(typed.scheduledtasks(), locale),
                ServerInfo.current(locale));
    }
}
