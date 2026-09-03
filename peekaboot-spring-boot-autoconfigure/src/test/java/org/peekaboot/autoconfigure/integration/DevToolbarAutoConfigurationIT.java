package org.peekaboot.autoconfigure.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.devtoolbar.ToolbarDataProvider;
import org.peekaboot.backend.filter.DevToolbarFilter;
import org.peekaboot.backend.insights.InsightsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * Boots a real application with actuator and OpenTelemetry on the classpath to prove the
 * auto-configuration order: the toolbar filters exist only if DevToolbarAutoConfiguration
 * runs after OpenTelemetryTracingAutoConfiguration has created the Tracer, and Insights
 * exists only if InsightsAutoConfiguration runs after Boot's metrics chain has created the
 * MeterRegistry. The bar's injection into HTML responses is checked over HTTP.
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
class DevToolbarAutoConfigurationIT {

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext context;

    private RestClient restClient;

    @BeforeEach
    void setUp() {
        restClient = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void tracerBeanShouldBeCreatedByOpenTelemetryAutoConfiguration() {
        assertThat(context.getBeanNamesForType(Tracer.class)).hasSize(1);
    }

    /**
     * Insights backs off without a MeterRegistry bean, and that bean comes from Boot's own
     * metrics auto-configuration - so the auto-configuration order has to put Peekaboot after
     * it, which nothing on this test classpath does by accident.
     */
    @Test
    void insightsServiceShouldBeCreatedAfterBootsMeterRegistry() {
        assertThat(context.getBeanNamesForType(MeterRegistry.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(InsightsService.class)).hasSize(1);
    }

    @Test
    void toolbarDataProviderShouldBeCreated() {
        assertThat(context.getBeanNamesForType(ToolbarDataProvider.class)).hasSize(1);
    }

    @Test
    void devToolbarFilterShouldBeRegistered() {
        FilterRegistrationBean<?> filter = context.getBean("devToolbarFilter", FilterRegistrationBean.class);
        assertThat(filter.getFilter()).isInstanceOf(DevToolbarFilter.class);
    }

    @Test
    void toolbarShouldBeInjectedIntoHtmlResponseWithARealTraceId() {
        String response = restClient
                .get()
                .uri("/test")
                .accept(MediaType.TEXT_HTML)
                .retrieve()
                .body(String.class);

        assertThat(response).contains("<!-- Peekaboot Dev Toolbar -->");
        assertThat(response).contains("peekaboot-toolbar-data");
        assertThat(response).matches("(?s).*\"traceId\":\"[a-f0-9]{32}\".*");
    }

    @Test
    void toolbarShouldNotBeInjectedForJsonResponses() {
        String response = restClient
                .get()
                .uri("/api/data")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class);

        assertThat(response).doesNotContain("Peekaboot");
    }
}
