package net.osslabz.peekaboot.backend.service;

import net.osslabz.peekaboot.backend.actuator.raw.ActuatorRawMapper;
import net.osslabz.peekaboot.backend.api.insights.ActuatorInsightsResponse;
import net.osslabz.peekaboot.backend.domain.health.HealthStatus;
import net.osslabz.peekaboot.backend.lifecycle.DataSourceMetadata;
import net.osslabz.peekaboot.backend.mapper.actuator.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ActuatorInsightsServiceTest {

    private PeekabookActuatorService rawService;
    private ActuatorInsightsService insightsService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        rawService = mock(PeekabookActuatorService.class);
        ObjectProvider<List<DataSourceMetadata>> dataSourceProvider = mock(ObjectProvider.class);
        when(dataSourceProvider.getIfAvailable(any())).thenReturn(List.of());

        insightsService = new ActuatorInsightsService(
            rawService,
            new ActuatorRawMapper(),
            new HealthMapper(),
            new RuntimeMapper(),
            new DataSourceMapper(),
            new ApplicationMapper(),
            new EnvironmentMapper(),
            new LoggersMapper(),
            new FlywayMapper(),
            new ConfigMapper(),
            new ScheduledTasksMapper(),
            dataSourceProvider
        );
    }

    @Test
    void getInsights_shouldMapAllSections() {
        when(rawService.getData()).thenReturn(Map.of(
            "health", Map.of("body", Map.of("status", "UP", "components", Map.of()), "status", 200),
            "info", Map.of("build", Map.of("name", "test")),
            "spring", Map.of("bootVersion", "4.0.1"),
            "env", Map.of("activeProfiles", List.of("dev")),
            "loggers", Map.of("loggers", Map.of()),
            "flyway", Map.of("contexts", Map.of()),
            "configprops", Map.of("contexts", Map.of())
        ));

        ActuatorInsightsResponse response = insightsService.getInsights();

        assertThat(response.health().status()).isEqualTo(HealthStatus.UP);
        assertThat(response.application().springBootVersion()).isEqualTo("4.0.1");
        assertThat(response.environment().activeProfiles()).containsExactly("dev");
    }

    @Test
    void getInsights_shouldHandleMissingData() {
        when(rawService.getData()).thenReturn(Map.of());

        ActuatorInsightsResponse response = insightsService.getInsights();

        assertThat(response.health().status()).isEqualTo(HealthStatus.UNKNOWN);
        assertThat(response.dataSources()).isEmpty();
    }
}
