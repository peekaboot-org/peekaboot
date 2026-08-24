package org.peekaboot.backend.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.peekaboot.backend.actuator.parsed.ActuatorParsedData;
import org.peekaboot.backend.actuator.parsed.ActuatorResponseParser;
import org.peekaboot.backend.api.insights.ActuatorInsightsResponse;
import org.peekaboot.backend.domain.server.ServerInfo;
import org.peekaboot.backend.lifecycle.DataSourceMetadata;
import org.peekaboot.backend.mapper.actuator.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public final class ActuatorInsightsService {

    private final PeekabootActuatorService actuatorService;
    private final ActuatorResponseParser responseParser;
    private final HealthMapper healthMapper;
    private final RuntimeMapper runtimeMapper;
    private final DataSourceMapper dataSourceMapper;
    private final ApplicationMapper applicationMapper;
    private final EnvironmentMapper environmentMapper;
    private final LoggersMapper loggersMapper;
    private final FlywayMapper flywayMapper;
    private final ConfigMapper configMapper;
    private final ScheduledTasksMapper scheduledTasksMapper;
    private final List<DataSourceMetadata> dataSourceMetadataList;

    public ActuatorInsightsService(
            PeekabootActuatorService actuatorService,
            ActuatorResponseParser responseParser,
            HealthMapper healthMapper,
            RuntimeMapper runtimeMapper,
            DataSourceMapper dataSourceMapper,
            ApplicationMapper applicationMapper,
            EnvironmentMapper environmentMapper,
            LoggersMapper loggersMapper,
            FlywayMapper flywayMapper,
            ConfigMapper configMapper,
            ScheduledTasksMapper scheduledTasksMapper,
            ObjectProvider<List<DataSourceMetadata>> dataSourceMetadataListProvider) {
        this.actuatorService = actuatorService;
        this.responseParser = responseParser;
        this.healthMapper = healthMapper;
        this.runtimeMapper = runtimeMapper;
        this.dataSourceMapper = dataSourceMapper;
        this.applicationMapper = applicationMapper;
        this.environmentMapper = environmentMapper;
        this.loggersMapper = loggersMapper;
        this.flywayMapper = flywayMapper;
        this.configMapper = configMapper;
        this.scheduledTasksMapper = scheduledTasksMapper;
        this.dataSourceMetadataList = dataSourceMetadataListProvider.getIfAvailable(List::of);
    }

    /**
     * {@code unmask} is the caller's already-resolved decision (from
     * {@code peekaboot.enable-unmasking} and the request's {@code unmask} parameter,
     * combined once in PeekabootController) - threaded to every mapper here that carries
     * property values, so none of them re-derives it.
     */
    public ActuatorInsightsResponse getInsights(Locale locale, boolean unmask) {
        Map<String, Object> rawData = actuatorService.getInsightsData();
        ActuatorParsedData typed = responseParser.parse(rawData);

        return new ActuatorInsightsResponse(
                applicationMapper.map(typed.info(), typed.spring(), unmask),
                runtimeMapper.map(typed.info(), typed.health()),
                dataSourceMapper.map(dataSourceMetadataList, typed.health(), unmask),
                healthMapper.map(typed.health(), unmask),
                environmentMapper.map(typed.env(), unmask),
                loggersMapper.map(typed.loggers()),
                flywayMapper.map(typed.flyway()),
                configMapper.map(typed.configprops(), unmask),
                scheduledTasksMapper.map(typed.scheduledtasks(), locale),
                ServerInfo.current(locale));
    }
}
