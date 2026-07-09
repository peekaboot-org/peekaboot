package net.osslabz.peekaboot.backend.service;

import net.osslabz.peekaboot.backend.fixture.TestFixtureApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

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
class PeekabookActuatorServiceTest {

    @Autowired
    private PeekabookActuatorService service;

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
}
