package net.osslabz.peekaboot.backend.service;

import net.osslabz.peekaboot.backend.api.insights.ActuatorInsightsResponse;
import net.osslabz.peekaboot.backend.lifecycle.DataSourceMetadata;
import net.osslabz.peekaboot.backend.mapper.actuator.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class ActuatorInsightsService {

    private final PeekabookActuatorService rawService;
    private final HealthMapper healthMapper;
    private final RuntimeMapper runtimeMapper;
    private final DataSourceMapper dataSourceMapper;
    private final ApplicationMapper applicationMapper;
    private final EnvironmentMapper environmentMapper;
    private final LoggersMapper loggersMapper;
    private final FlywayMapper flywayMapper;
    private final ConfigMapper configMapper;
    private final List<DataSourceMetadata> dataSourceMetadataList;

    public ActuatorInsightsService(
            PeekabookActuatorService rawService,
            HealthMapper healthMapper,
            RuntimeMapper runtimeMapper,
            DataSourceMapper dataSourceMapper,
            ApplicationMapper applicationMapper,
            EnvironmentMapper environmentMapper,
            LoggersMapper loggersMapper,
            FlywayMapper flywayMapper,
            ConfigMapper configMapper,
            ObjectProvider<List<DataSourceMetadata>> dataSourceMetadataListProvider) {
        this.rawService = rawService;
        this.healthMapper = healthMapper;
        this.runtimeMapper = runtimeMapper;
        this.dataSourceMapper = dataSourceMapper;
        this.applicationMapper = applicationMapper;
        this.environmentMapper = environmentMapper;
        this.loggersMapper = loggersMapper;
        this.flywayMapper = flywayMapper;
        this.configMapper = configMapper;
        this.dataSourceMetadataList = dataSourceMetadataListProvider.getIfAvailable(List::of);
    }

    @SuppressWarnings("unchecked")
    public ActuatorInsightsResponse getInsights() {
        Map<String, Object> rawData = rawService.getData();

        // Extract health components for cross-referencing
        Map<String, Object> healthComponents = extractHealthComponents(rawData);

        return new ActuatorInsightsResponse(
            applicationMapper.map(asMap(rawData.get("info")), asMap(rawData.get("spring"))),
            runtimeMapper.map(asMap(rawData.get("info")), healthComponents),
            dataSourceMapper.map(dataSourceMetadataList, healthComponents),
            healthMapper.map(asMap(rawData.get("health"))),
            environmentMapper.map(asMap(rawData.get("env"))),
            loggersMapper.map(asMap(rawData.get("loggers"))),
            flywayMapper.map(asMap(rawData.get("flyway"))),
            configMapper.map(asMap(rawData.get("configprops")))
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractHealthComponents(Map<String, Object> rawData) {
        Object healthObj = rawData.get("health");
        if (!(healthObj instanceof Map<?, ?> health)) return Map.of();

        // Handle body wrapper
        if (health.containsKey("body")) {
            health = (Map<?, ?>) health.get("body");
        }

        Object components = health.get("components");
        if (components instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object obj) {
        if (obj instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return null;
    }
}
