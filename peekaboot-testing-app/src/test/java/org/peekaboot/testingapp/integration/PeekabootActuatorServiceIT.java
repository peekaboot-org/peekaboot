package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.osslabz.jdbc.Host;
import net.osslabz.jdbc.JdbcProperty;
import net.osslabz.jdbc.PropertySource;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.lifecycle.DataSourceMetadata;
import org.peekaboot.backend.service.PeekabootActuatorService;
import org.peekaboot.testingapp.TestingApp;
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

    @Test
    void insightsDataInvokesOnlyConsumedEndpoints() {
        Map<String, Object> data = service.getInsightsData();

        // spring and dataSources are built locally, the rest must be limited to
        // the endpoints the insights mappers actually consume.
        Set<String> allowed = Set.of(
                "spring", "dataSources", "health", "info", "env", "loggers", "flyway", "configprops", "scheduledtasks");
        assertThat(data.keySet()).isSubsetOf(allowed);
        assertThat(data).containsKeys("health", "info", "env");
        assertThat(data).doesNotContainKeys("beans", "conditions", "mappings", "threaddump", "metrics");
    }

    @Test
    void insightsDataCapturesEndpointInvocationExceptionAsErrorMessage() {
        Map<String, Object> data = service.getInsightsData();

        assertThat(data.get("loggers")).isEqualTo("Error: boom");
    }

    @Test
    @SuppressWarnings("unchecked")
    void insightsDataFormatsDataSourceHostsAndConnectionParams() {
        Map<String, Object> data = service.getInsightsData();

        List<Map<String, Object>> dataSources = (List<Map<String, Object>>) data.get("dataSources");
        assertThat(dataSources).isNotEmpty();

        Map<String, Object> dataSource = dataSources.get(0);
        assertThat(dataSource.get("name")).isEqualTo("primary");

        // A real Host is stubbed on the fixture so the host.toString() formatting
        // loop (buildDataSourcesInfo()) actually runs, not just an empty ArrayList.
        List<String> hosts = (List<String>) dataSource.get("hosts");
        assertThat(hosts).containsExactly("db.example.com:5432");

        Map<String, Object> connectionParams = (Map<String, Object>) dataSource.get("connectionParams");
        assertThat(connectionParams).isNotNull();
        assertThat(connectionParams).isNotEmpty();
        connectionParams
                .values()
                .forEach(
                        paramInfo -> assertThat((Map<String, Object>) paramInfo).containsKeys("value", "source"));
        assertThat(((Map<String, Object>) connectionParams.get("MODE")).get("value"))
                .isEqualTo("MEMORY");

        // getInsightsData() is the unmasked, low-level accessor - masking happens further
        // downstream in ActuatorInsightsService's typed mappers (see DataSourceMapperTest
        // and ActuatorMaskingIT), not here.
        assertThat(((Map<String, Object>) connectionParams.get("password")).get("value"))
                .isEqualTo("hunter2");
    }

    /**
     * Overrides the real "loggers" actuator endpoint with a bean that always throws, to
     * pin PeekabootActuatorService#collectData's exception-capture branch - the guarantee
     * that one broken endpoint doesn't break the whole insights payload. Named after a
     * real INSIGHTS_ENDPOINTS id (not a synthetic one) because getInsightsData() only
     * invokes endpoints in that set; a fresh id would never reach the catch block.
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
     * is used instead of a live DataSource so a real {@link Host} can be stubbed in
     * — the H2 in-memory URL this module's test DataSource actually uses never yields
     * a host, which would leave {@code buildDataSourcesInfo()}'s host.toString()
     * formatting loop unexercised.
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
