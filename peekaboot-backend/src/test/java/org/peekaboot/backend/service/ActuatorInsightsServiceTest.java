package org.peekaboot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.actuator.InsightsSource;
import org.peekaboot.backend.actuator.parsed.ActuatorResponseParser;
import org.peekaboot.backend.domain.health.HealthStatus;
import org.peekaboot.backend.domain.insights.ActuatorInsightsResponse;
import org.peekaboot.backend.domain.loggers.LoggerGroup;
import org.peekaboot.backend.lifecycle.DataSourceMetadata;
import org.peekaboot.backend.lifecycle.DataSourceMetadataList;
import org.peekaboot.backend.masking.MaskingEngine;
import org.springframework.beans.factory.ObjectProvider;

class ActuatorInsightsServiceTest {

    @Test
    void getInsights_shouldMapAllSections() {
        ActuatorInsightsService service = service(
                Map.of(
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
                                                                        Map.of("enabled", true))))))),
                DataSourceMetadataList.EMPTY);

        ActuatorInsightsResponse response = service.getInsights(Locale.ENGLISH, false);

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
    void getInsights_shouldMapDataSourcesFromInjectedMetadata() {
        DataSourceMetadata metadata = mock(DataSourceMetadata.class);
        when(metadata.getDataSourceName()).thenReturn("primaryDS");
        when(metadata.getHosts()).thenReturn(List.of());
        ActuatorInsightsService service = service(Map.of(), new DataSourceMetadataList(List.of(metadata)));

        ActuatorInsightsResponse response = service.getInsights(Locale.ENGLISH, false);

        assertThat(response.dataSources()).hasSize(1);
        assertThat(response.dataSources().get(0).name()).isEqualTo("primaryDS");
    }

    @Test
    void getInsights_shouldHandleMissingData() {
        ActuatorInsightsService service = service(Map.of(), DataSourceMetadataList.EMPTY);

        ActuatorInsightsResponse response = service.getInsights(Locale.ENGLISH, false);

        assertThat(response.health().status()).isEqualTo(HealthStatus.UNKNOWN);
        assertThat(response.dataSources()).isEmpty();
        assertThat(response.server()).isNotNull();
    }

    /** The mappers are built inside the service, so the engine it is handed is the one that masks. */
    @Test
    void getInsights_shouldMaskThroughTheEngineItWasGiven() {
        ActuatorInsightsService service = service(
                Map.of(
                        "env",
                        Map.of(
                                "propertySources",
                                List.of(Map.of(
                                        "name",
                                        "application.yml",
                                        "properties",
                                        Map.of("db.password", Map.of("value", "hunter2")))))),
                DataSourceMetadataList.EMPTY);

        ActuatorInsightsResponse response = service.getInsights(Locale.ENGLISH, false);

        assertThat(response.environment()
                        .propertySources()
                        .getFirst()
                        .properties()
                        .getFirst()
                        .value())
                .isEqualTo("******");
    }

    @Test
    void getInsights_shouldDescribeSchedulesInTheRequestLocale() {
        ActuatorInsightsService service = service(
                Map.of(
                        "scheduledtasks",
                        Map.of(
                                "cron",
                                List.of(Map.of(
                                        "expression",
                                        "0 0 * * * *",
                                        "runnable",
                                        Map.of("target", "com.example.Task.run"))))),
                DataSourceMetadataList.EMPTY);

        ActuatorInsightsResponse response = service.getInsights(Locale.GERMAN, false);

        assertThat(response.scheduledTasks().tasks().getFirst().scheduleDescription())
                .containsIgnoringCase("Stunde");
    }

    /** The real actuator service over inline sources, one per section the test supplies. */
    @SuppressWarnings("unchecked")
    private static ActuatorInsightsService service(Map<String, Object> sections, DataSourceMetadataList dataSources) {
        List<InsightsSource> sources = sections.entrySet().stream()
                .map(section -> new InsightsSource(section.getKey(), section::getValue))
                .toList();
        ObjectProvider<DataSourceMetadataList> dataSourceProvider = mock(ObjectProvider.class);
        when(dataSourceProvider.getIfAvailable(any())).thenReturn(dataSources);
        return new ActuatorInsightsService(
                new PeekabootActuatorService(sources),
                new ActuatorResponseParser(),
                new MaskingEngine(),
                dataSourceProvider);
    }
}
