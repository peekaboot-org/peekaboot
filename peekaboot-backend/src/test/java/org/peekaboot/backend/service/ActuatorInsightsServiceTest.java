package org.peekaboot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.actuator.parsed.ActuatorResponseParser;
import org.peekaboot.backend.domain.health.HealthStatus;
import org.peekaboot.backend.domain.insights.ActuatorInsightsResponse;
import org.peekaboot.backend.domain.loggers.LoggerGroup;
import org.peekaboot.backend.lifecycle.DataSourceMetadata;
import org.peekaboot.backend.mapper.actuator.ApplicationMapper;
import org.peekaboot.backend.mapper.actuator.ConfigMapper;
import org.peekaboot.backend.mapper.actuator.DataSourceMapper;
import org.peekaboot.backend.mapper.actuator.EnvironmentMapper;
import org.peekaboot.backend.mapper.actuator.FlywayMapper;
import org.peekaboot.backend.mapper.actuator.HealthMapper;
import org.peekaboot.backend.mapper.actuator.LoggersMapper;
import org.peekaboot.backend.mapper.actuator.RuntimeMapper;
import org.peekaboot.backend.mapper.actuator.ScheduledTasksMapper;
import org.peekaboot.backend.masking.MaskingEngine;
import org.springframework.beans.factory.ObjectProvider;

class ActuatorInsightsServiceTest {

    private PeekabootActuatorService actuatorService;
    private ActuatorInsightsService insightsService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        actuatorService = mock(PeekabootActuatorService.class);
        ObjectProvider<List<DataSourceMetadata>> dataSourceProvider = mock(ObjectProvider.class);
        when(dataSourceProvider.getIfAvailable(any())).thenReturn(List.of());

        insightsService = newInsightsService(dataSourceProvider);
    }

    @Test
    void getInsights_shouldMapAllSections() {
        when(actuatorService.getInsightsData())
                .thenReturn(Map.of(
                        "health", Map.of("status", "UP", "components", Map.of()),
                        "info",
                                Map.of(
                                        "build", Map.of("name", "test"),
                                        "os", Map.of("name", "Linux", "version", "5.15", "arch", "amd64")),
                        "spring", Map.of("bootVersion", "4.0.1"),
                        "env", Map.of("activeProfiles", List.of("dev")),
                        "loggers", Map.of("loggers", Map.of("com.example.Foo", Map.of("effectiveLevel", "DEBUG"))),
                        "flyway",
                                Map.of(
                                        "contexts",
                                        Map.of(
                                                "application",
                                                Map.of(
                                                        "flywayBeans",
                                                        Map.of(
                                                                "flyway",
                                                                Map.of(
                                                                        "migrations",
                                                                        List.of(Map.of(
                                                                                "version",
                                                                                "1",
                                                                                "description",
                                                                                "Initial",
                                                                                "state",
                                                                                "SUCCESS"))))))),
                        "configprops",
                                Map.of(
                                        "contexts",
                                        Map.of(
                                                "application",
                                                Map.of(
                                                        "beans",
                                                        Map.of(
                                                                "myBean",
                                                                Map.of(
                                                                        "prefix",
                                                                        "my.config",
                                                                        "properties",
                                                                        Map.of("enabled", true))))))));

        ActuatorInsightsResponse response = insightsService.getInsights(Locale.ENGLISH, false);

        assertThat(response.health().status()).isEqualTo(HealthStatus.UP);
        assertThat(response.application().springBootVersion()).isEqualTo("4.0.1");
        assertThat(response.environment().activeProfiles()).containsExactly("dev");
        assertThat(response.runtime().os()).isNotNull();
        assertThat(response.runtime().os().name()).isEqualTo("Linux");
        assertThat(response.loggers().totalCount()).isEqualTo(1);
        assertThat(response.loggers().packages())
                .extracting(LoggerGroup::packageName)
                .containsExactly("com.example");
        assertThat(response.flyway().migrations()).hasSize(1);
        assertThat(response.flyway().migrations().get(0).version()).isEqualTo("1");
        assertThat(response.config().groups()).hasSize(1);
        assertThat(response.config().groups().get(0).prefix()).isEqualTo("my.config");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getInsights_shouldMapDataSourcesFromInjectedMetadata() {
        DataSourceMetadata metadata = mock(DataSourceMetadata.class);
        when(metadata.getDataSourceName()).thenReturn("primaryDS");
        when(metadata.getHosts()).thenReturn(List.of());

        ObjectProvider<List<DataSourceMetadata>> dataSourceProvider = mock(ObjectProvider.class);
        when(dataSourceProvider.getIfAvailable(any())).thenReturn(List.of(metadata));

        ActuatorInsightsService serviceWithDataSource = newInsightsService(dataSourceProvider);
        when(actuatorService.getInsightsData()).thenReturn(Map.of());

        ActuatorInsightsResponse response = serviceWithDataSource.getInsights(Locale.ENGLISH, false);

        assertThat(response.dataSources()).hasSize(1);
        assertThat(response.dataSources().get(0).name()).isEqualTo("primaryDS");
    }

    @Test
    void getInsights_shouldHandleMissingData() {
        when(actuatorService.getInsightsData()).thenReturn(Map.of());

        ActuatorInsightsResponse response = insightsService.getInsights(Locale.ENGLISH, false);

        assertThat(response.health().status()).isEqualTo(HealthStatus.UNKNOWN);
        assertThat(response.dataSources()).isEmpty();
    }

    @Test
    void getInsights_shouldIncludeServerInfo() {
        when(actuatorService.getInsightsData()).thenReturn(Map.of());

        ActuatorInsightsResponse response = insightsService.getInsights(Locale.ENGLISH, false);

        ZoneId zone = ZoneId.systemDefault();
        assertThat(response.server().timezone()).isEqualTo(zone.getId());
        assertThat(response.server().timezoneOffset())
                .isEqualTo(zone.getRules().getOffset(Instant.now()).toString());
        assertThat(response.server().timezoneDisplay()).isEqualTo(zone.getDisplayName(TextStyle.FULL, Locale.ENGLISH));
        assertThat(response.server().availableProcessors())
                .isEqualTo(Runtime.getRuntime().availableProcessors());
    }

    @Test
    void getInsights_shouldPassLocaleToScheduledTasksMapper() {
        when(actuatorService.getInsightsData())
                .thenReturn(Map.of(
                        "scheduledtasks",
                        Map.of(
                                "cron",
                                        List.of(Map.of(
                                                "expression",
                                                "0 0 * * * *",
                                                "runnable",
                                                Map.of("target", "com.example.Task.run"))),
                                "fixedDelay", List.of(),
                                "fixedRate", List.of())));

        ActuatorInsightsResponse response = insightsService.getInsights(Locale.ENGLISH, false);

        assertThat(response.scheduledTasks()).isNotNull();
        assertThat(response.scheduledTasks().tasks()).hasSize(1);
        assertThat(response.scheduledTasks().tasks().get(0).scheduleDescription())
                .isNotNull();
    }

    private ActuatorInsightsService newInsightsService(ObjectProvider<List<DataSourceMetadata>> dataSourceProvider) {
        MaskingEngine maskingEngine = new MaskingEngine();
        return new ActuatorInsightsService(
                actuatorService,
                new ActuatorResponseParser(),
                new HealthMapper(maskingEngine),
                new RuntimeMapper(),
                new DataSourceMapper(maskingEngine),
                new ApplicationMapper(maskingEngine),
                new EnvironmentMapper(maskingEngine),
                new LoggersMapper(),
                new FlywayMapper(),
                new ConfigMapper(maskingEngine),
                new ScheduledTasksMapper(),
                dataSourceProvider);
    }
}
