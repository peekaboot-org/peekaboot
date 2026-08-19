package org.peekaboot.testingapp.integration;

import net.osslabz.jdbc.Host;
import net.osslabz.jdbc.JdbcProperty;
import net.osslabz.jdbc.PropertySource;
import org.peekaboot.backend.lifecycle.DataSourceMetadata;
import org.peekaboot.backend.service.PeekabootActuatorService;
import org.peekaboot.testingapp.TestingApp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(
    classes = TestingApp.class,
    properties = {
        "management.endpoints.web.exposure.include=*",
        "management.endpoint.threaddump.access=unrestricted",
        "management.endpoint.heapdump.access=unrestricted"
    }
)
@ActiveProfiles("test")
@Import({
    PeekabootActuatorServiceTest.ThrowingEndpointConfig.class,
    PeekabootActuatorServiceTest.DataSourceMetadataFixtureConfig.class
})
class PeekabootActuatorServiceTest {

    @Autowired
    private PeekabootActuatorService service;

    @Autowired
    private ApplicationContext context;

    @Test
    void rawDataExcludesExpensiveEndpoints() {
        // Precondition: with exposure=* the expensive endpoints exist in the context,
        // so their absence from the result is due to filtering, not availability.
        assertThat(context.getBeansOfType(org.springframework.boot.actuate.management.ThreadDumpEndpoint.class)).isNotEmpty();
        assertThat(context.getBeansOfType(org.springframework.boot.actuate.management.HeapDumpWebEndpoint.class)).isNotEmpty();

        Map<String, Object> raw = service.getRawData();

        assertThat(raw).containsKey("health");
        assertThat(raw).doesNotContainKeys("threaddump", "heapdump", "logfile");
    }

    @Test
    void insightsDataInvokesOnlyConsumedEndpoints() {
        Map<String, Object> data = service.getInsightsData();

        // spring and dataSources are built locally, the rest must be limited to
        // the endpoints the insights mappers actually consume.
        Set<String> allowed = Set.of("spring", "dataSources",
                "health", "info", "env", "loggers", "flyway", "configprops", "scheduledtasks");
        assertThat(data.keySet()).isSubsetOf(allowed);
        assertThat(data).containsKeys("health", "info", "env");
        assertThat(data).doesNotContainKeys("beans", "conditions", "mappings", "threaddump", "metrics");
    }

    @Test
    void rawDataCapturesEndpointInvocationExceptionAsErrorMessage() {
        assertThat(context.getBeansOfType(ThrowingEndpoint.class)).isNotEmpty();

        Map<String, Object> raw = service.getRawData();

        assertThat(raw.get("throwingtest")).isEqualTo("Error: boom");
    }

    @Test
    @SuppressWarnings("unchecked")
    void rawDataFormatsDataSourceHostsAndConnectionParams() {
        Map<String, Object> raw = service.getRawData();

        List<Map<String, Object>> dataSources = (List<Map<String, Object>>) raw.get("dataSources");
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
        connectionParams.values().forEach(paramInfo ->
                assertThat((Map<String, Object>) paramInfo).containsKeys("value", "source"));
        assertThat(((Map<String, Object>) connectionParams.get("MODE")).get("value")).isEqualTo("MEMORY");
    }

    @TestConfiguration
    static class ThrowingEndpointConfig {
        @Bean
        ThrowingEndpoint throwingEndpoint() {
            return new ThrowingEndpoint();
        }
    }

    @Endpoint(id = "throwingtest")
    static class ThrowingEndpoint {
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
            when(metadata.getConnectionParams()).thenReturn(
                    Map.of("MODE", new JdbcProperty(PropertySource.DERIVED, "MEMORY")));
            return List.of(metadata);
        }
    }
}
