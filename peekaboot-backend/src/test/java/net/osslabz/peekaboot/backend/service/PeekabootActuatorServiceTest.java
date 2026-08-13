package net.osslabz.peekaboot.backend.service;

import net.osslabz.peekaboot.backend.fixture.TestFixtureApplication;
import net.osslabz.peekaboot.backend.lifecycle.DataSourceMetadata;
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

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = TestFixtureApplication.class,
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
        assertThat(dataSource.get("name")).isNotNull();
        assertThat(dataSource.get("hosts")).isInstanceOf(List.class);

        Map<String, Object> connectionParams = (Map<String, Object>) dataSource.get("connectionParams");
        assertThat(connectionParams).isNotNull();
        // The H2 in-memory URL carries a derived MODE parameter, so
        // buildDataSourcesInfo()'s per-param value/source formatting actually runs.
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
     * peekaboot-spring-boot-autoconfigure (which normally supplies the real
     * {@code List<DataSourceMetadata>} bean) is not a dependency of this module,
     * so this fixture reproduces that wiring against the test's real DataSource
     * to exercise {@code buildDataSourcesInfo()}'s formatting with real data.
     */
    @TestConfiguration
    static class DataSourceMetadataFixtureConfig {
        @Bean
        List<DataSourceMetadata> testDataSourceMetadataList(DataSource dataSource) {
            return DataSourceMetadata.fromDataSource("primary", dataSource)
                    .map(List::of)
                    .orElse(List.of());
        }
    }
}
