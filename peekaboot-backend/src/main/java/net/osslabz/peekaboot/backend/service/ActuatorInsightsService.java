package net.osslabz.peekaboot.backend.service;

import net.osslabz.peekaboot.backend.actuator.raw.ActuatorParsedData;
import net.osslabz.peekaboot.backend.actuator.raw.ActuatorRawMapper;
import net.osslabz.peekaboot.backend.api.insights.ActuatorInsightsResponse;
import net.osslabz.peekaboot.backend.lifecycle.DataSourceMetadata;
import net.osslabz.peekaboot.backend.domain.server.ServerInfo;
import net.osslabz.peekaboot.backend.mapper.actuator.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ActuatorInsightsService {

    private final PeekabookActuatorService rawService;
    private final ActuatorRawMapper rawMapper;
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
            PeekabookActuatorService rawService,
            ActuatorRawMapper rawMapper,
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
        this.rawService = rawService;
        this.rawMapper = rawMapper;
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

    public ActuatorInsightsResponse getInsights(Locale locale) {
        Map<String, Object> rawData = rawService.getInsightsData();
        ActuatorParsedData typed = rawMapper.map(rawData);

        return new ActuatorInsightsResponse(
            applicationMapper.map(typed.info(), typed.spring()),
            runtimeMapper.map(typed.info(), typed.health()),
            dataSourceMapper.map(dataSourceMetadataList, typed.health()),
            healthMapper.map(typed.health()),
            environmentMapper.map(typed.env()),
            loggersMapper.map(typed.loggers()),
            flywayMapper.map(typed.flyway()),
            configMapper.map(typed.configprops()),
            scheduledTasksMapper.map(typed.scheduledtasks(), locale),
            ServerInfo.current(locale)
        );
    }
}
