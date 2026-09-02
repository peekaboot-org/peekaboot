package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.osslabz.jdbc.Host;
import net.osslabz.jdbc.JdbcProperty;
import net.osslabz.jdbc.PropertySource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.datasource.DataSourceInfo;
import org.peekaboot.backend.lifecycle.DataSourceMetadata;
import org.peekaboot.backend.service.ActuatorInsightsService;
import org.peekaboot.backend.service.PeekabootActuatorService;
import org.peekaboot.testingapp.TestingApp;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
        classes = TestingApp.class,
        properties = {
            "management.endpoints.web.exposure.include=*",
            // ThrowingLoggersEndpoint below stands in for the real "loggers" endpoint - both
            // sharing the id would otherwise fail context startup ("Found two endpoints with
            // the id 'loggers'").
            // An inlined property replaces application-test.yml's value for the same key
            // rather than merging with it, so this list has to repeat that file's servlet
            // security exclusions too - without them this context alone would start with
            // Spring Security auto-configured. Anything added there belongs here as well.
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.actuate.autoconfigure.logging.LoggersEndpointAutoConfiguration,"
                    + "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration,"
                    + "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration,"
                    + "org.springframework.boot.security.autoconfigure.actuate.web.servlet"
                    + ".ManagementWebSecurityAutoConfiguration"
        })
@ActiveProfiles("test")
@Import({
    PeekabootActuatorServiceIT.DataSourceMetadataFixtureConfig.class,
    PeekabootActuatorServiceIT.ThrowingEndpointConfig.class
})
class PeekabootActuatorServiceIT {

    @Autowired
    private PeekabootActuatorService service;

    @Autowired
    private ActuatorInsightsService insightsService;

    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(PeekabootActuatorService.class);
    private final ListAppender<ILoggingEvent> serviceLog = new ListAppender<>();
    private boolean additivity;
    private Level level;

    // The throwing endpoint makes every getInsightsData() call log its failure - WARN with
    // the cause the first time, DEBUG afterwards; capture both so they can be asserted on
    // and never reach the console.
    @BeforeEach
    void captureServiceLog() {
        additivity = serviceLogger.isAdditive();
        level = serviceLogger.getLevel();
        serviceLogger.setAdditive(false);
        serviceLogger.setLevel(Level.DEBUG);
        serviceLog.start();
        serviceLogger.addAppender(serviceLog);
    }

    @AfterEach
    void releaseServiceLog() {
        serviceLogger.detachAppender(serviceLog);
        serviceLogger.setLevel(level);
        serviceLogger.setAdditive(additivity);
    }

    @Test
    void insightsDataInvokesOnlyConsumedEndpoints() {
        Map<String, Object> data = service.getInsightsData();

        // spring is built locally, the rest must be limited to the endpoints the insights
        // mappers actually consume.
        Set<String> allowed =
                Set.of("spring", "health", "info", "env", "loggers", "flyway", "configprops", "scheduledtasks");
        assertThat(data.keySet()).isSubsetOf(allowed);
        assertThat(data).containsKeys("health", "info", "env");
        assertThat(data).doesNotContainKeys("beans", "conditions", "mappings", "threaddump", "metrics");
    }

    @Test
    void aFailingEndpointIsLeftOutAndLoggedWithoutBreakingTheOthers() {
        Map<String, Object> data = service.getInsightsData();

        assertThat(data).doesNotContainKey("loggers");
        assertThat(data).containsKeys("health", "info", "env");
        // Whether this call is the endpoint's first failure in the shared context depends on
        // test order, so either line is acceptable; PeekabootActuatorServiceTest pins both.
        assertThat(serviceLog.list).anySatisfy(event -> {
            assertThat(event.getFormattedMessage()).contains("'loggers' failed");
            String cause = event.getThrowableProxy() != null
                    ? event.getThrowableProxy().getMessage()
                    : event.getFormattedMessage();
            assertThat(cause).contains("boom");
        });
    }

    @Test
    void dataSourceConnectionParamsReachTheInsightsMaskedByKey() {
        List<DataSourceInfo> dataSources =
                insightsService.getInsights(Locale.ENGLISH, false).dataSources();

        assertThat(dataSources).hasSize(1);
        DataSourceInfo dataSource = dataSources.getFirst();
        assertThat(dataSource.name()).isEqualTo("primary");
        assertThat(dataSource.hosts()).extracting(Host::toString).containsExactly("db.example.com:5432");
        assertThat(dataSource.properties()).containsEntry("MODE", "MEMORY").containsEntry("password", "******");
    }

    /**
     * Overrides the real "loggers" actuator endpoint with a bean that always throws, to
     * pin the guarantee that one broken endpoint doesn't break the whole insights payload.
     * Named after a real INSIGHTS_ENDPOINTS id (not a synthetic one) because
     * getInsightsData() only invokes endpoints in that set; a fresh id would never reach
     * the catch block.
     */
    @TestConfiguration
    static class ThrowingEndpointConfig {
        @Bean
        ThrowingLoggersEndpoint throwingLoggersEndpoint() {
            return new ThrowingLoggersEndpoint();
        }
    }

    @Endpoint(id = "loggers")
    static class ThrowingLoggersEndpoint {
        @ReadOperation
        public String read() {
            throw new IllegalStateException("boom");
        }
    }

    /**
     * The bean name matches the one {@code PeekabootLifecycleAutoConfiguration}
     * guards with {@code @ConditionalOnMissingBean(name = "databaseMetadataList")},
     * so this fixture bean wins over the real one and this module's real, H2-backed
     * DataSource is never consulted. A mock (same pattern as {@code DataSourceMapperTest})
     * is used instead of a live DataSource so a real {@link Host} and a password
     * parameter can be stubbed in - the H2 in-memory URL this module's test DataSource
     * actually uses yields neither.
     */
    @TestConfiguration
    static class DataSourceMetadataFixtureConfig {
        @Bean
        List<DataSourceMetadata> databaseMetadataList() {
            DataSourceMetadata metadata = mock(DataSourceMetadata.class);
            when(metadata.getDataSourceName()).thenReturn("primary");
            when(metadata.getHosts()).thenReturn(List.of(new Host("db.example.com", 5432, null)));
            when(metadata.getConnectionParams())
                    .thenReturn(Map.of(
                            "MODE", new JdbcProperty(PropertySource.DERIVED, "MEMORY"),
                            "password", new JdbcProperty(PropertySource.QUERY, "hunter2")));
            return List.of(metadata);
        }
    }
}
