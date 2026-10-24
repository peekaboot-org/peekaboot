package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.osslabz.jdbc.DatabaseProduct;
import net.osslabz.jdbc.Host;
import net.osslabz.jdbc.JdbcProperty;
import net.osslabz.jdbc.PropertySource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.actuator.ActuatorReadings;
import org.peekaboot.backend.actuator.InsightsSource;
import org.peekaboot.backend.actuator.parsed.ActuatorParsedData;
import org.peekaboot.backend.datasource.DataSourceMetadata;
import org.peekaboot.backend.datasource.DataSourceMetadataList;
import org.peekaboot.backend.domain.datasource.DataSourceInfo;
import org.peekaboot.backend.service.ActuatorInsightsService;
import org.peekaboot.testingapp.TestingApp;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = TestingApp.class)
@ActiveProfiles("test")
@Import({ActuatorReadingsIT.DataSourceMetadataFixtureConfig.class, ActuatorReadingsIT.ThrowingSourceConfig.class})
class ActuatorReadingsIT {

    @Autowired
    private ActuatorReadings readings;

    @Autowired
    private ActuatorInsightsService insightsService;

    private LogCapture readingsLog;

    // The throwing endpoint makes every readAll() call log its failure - WARN with
    // the cause the first time, DEBUG afterwards; capture both so they can be asserted on
    // and never reach the console.
    @BeforeEach
    void captureServiceLog() {
        readingsLog = LogCapture.attach(ActuatorReadings.class, Level.DEBUG);
    }

    @AfterEach
    void releaseServiceLog() {
        readingsLog.close();
    }

    // flyway.enabled: false in the test profile leaves the flyway source absent from
    // data.keySet(), so this can only assert a subset, not equality; deriving allowed from
    // ActuatorParsedData's own record components means an id that stops matching one fails
    // here instead of silently feeding the mappers a null.
    // liquibase is present: the test profile migrates the inventory DataSource.
    @Test
    void insightsDataIsASubsetOfTheConsumedEndpointIds() {
        Map<String, Object> data = readings.readAll();

        Set<String> allowed = Arrays.stream(ActuatorParsedData.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
        assertThat(data.keySet()).isSubsetOf(allowed);
        assertThat(data).containsKeys("health", "info", "env", "liquibase");
    }

    /**
     * The repeat call is what this asserts on: {@code reportedFailures} lives on the
     * bean instance, so whether the first call of a method is the context's first
     * failure depends on test order, but a call made after one in the same method never
     * is. That pins the DEBUG half of "WARN once, DEBUG afterwards" against a real
     * container; ActuatorReadingsTest pins the WARN and its cause.
     */
    @Test
    void aFailingEndpointIsLeftOutAndLoggedWithoutBreakingTheOthers() {
        readings.readAll();
        readingsLog.appender().list.clear();

        Map<String, Object> data = readings.readAll();

        assertThat(data).doesNotContainKey("loggers");
        assertThat(data).containsKeys("health", "info", "env");
        assertThat(readingsLog.appender().list)
                .filteredOn(event -> event.getFormattedMessage().contains("'loggers' failed"))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                    assertThat(event.getFormattedMessage()).contains("boom");
                });
    }

    @Test
    void dataSourceConnectionParamsReachTheInsightsMaskedByKey() {
        List<DataSourceInfo> dataSources =
                insightsService.getInsights(Locale.ENGLISH, false).dataSources();

        assertThat(dataSources).hasSize(1);
        DataSourceInfo dataSource = dataSources.getFirst();
        assertThat(dataSource.name()).isEqualTo("primary");
        assertThat(dataSource.hosts()).containsExactly(new DataSourceInfo.Host("db.example.com", 5432));
        assertThat(dataSource.properties()).containsEntry("MODE", "MEMORY").containsEntry("password", "******");
    }

    /**
     * Replaces Peekaboot's own loggers source by bean name - the way an application would -
     * to pin that one broken source does not break the rest of the insights payload.
     */
    @TestConfiguration
    static class ThrowingSourceConfig {
        @Bean
        InsightsSource loggersInsightsSource() {
            return new InsightsSource("loggers", () -> {
                throw new IllegalStateException("boom");
            });
        }
    }

    /**
     * {@code PeekabootLifecycleAutoConfiguration} backs its {@code DataSourceMetadataList}
     * bean off for one of the same type, so this fixture bean wins over the real one and
     * this module's real, H2-backed DataSource is never consulted. The record is built by
     * hand rather than read off a live DataSource so it carries a real {@link Host} and a
     * password parameter - the H2 in-memory URL this module's test DataSource actually
     * uses yields neither.
     */
    @TestConfiguration
    static class DataSourceMetadataFixtureConfig {
        @Bean
        DataSourceMetadataList dataSourceMetadataList() {
            DataSourceMetadata metadata = new DataSourceMetadata(
                    "primary",
                    "app",
                    List.of(new Host("db.example.com", 5432, null)),
                    "orders",
                    DatabaseProduct.POSTGRESQL,
                    Map.of(
                            "MODE", new JdbcProperty(PropertySource.DERIVED, "MEMORY"),
                            "password", new JdbcProperty(PropertySource.QUERY, "hunter2")),
                    "PostgreSQL",
                    "16",
                    "PostgreSQL JDBC Driver");
            return new DataSourceMetadataList(List.of(metadata));
        }
    }
}
