package org.peekaboot.autoconfigure.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.devtoolbar.ToolbarDataProvider;
import org.peekaboot.backend.filter.DevToolbarFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * Integration test for DevToolbarAutoConfiguration with real OpenTelemetry tracing.
 * This test verifies that auto-configuration correctly creates beans when all
 * conditions are met, including proper ordering after OpenTelemetry auto-configuration.
 *
 * This test would have caught the auto-configuration ordering bug where
 * DevToolbarAutoConfiguration was evaluated before OpenTelemetryTracingAutoConfiguration
 * created the Tracer bean.
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
        assertThat(context.getBean(Tracer.class)).isNotNull();
    }

    @Test
    void toolbarDataProviderShouldBeCreated() {
        assertThat(context.getBean(ToolbarDataProvider.class)).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void devToolbarFilterShouldBeRegistered() {
        FilterRegistrationBean<DevToolbarFilter> filter =
                (FilterRegistrationBean<DevToolbarFilter>) context.getBean("devToolbarFilter");
        assertThat(filter).isNotNull();
        assertThat(filter.getFilter()).isInstanceOf(DevToolbarFilter.class);
    }

    @Test
    void toolbarShouldBeInjectedIntoHtmlResponse() {
        String response = restClient
                .get()
                .uri("/test")
                .accept(MediaType.TEXT_HTML)
                .retrieve()
                .body(String.class);

        assertThat(response).contains("<!-- Peekaboot Dev Toolbar -->");
        assertThat(response).contains("peekaboot-toolbar-data");
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

    @Test
    void toolbarShouldContainTraceId() {
        String response = restClient
                .get()
                .uri("/test")
                .accept(MediaType.TEXT_HTML)
                .retrieve()
                .body(String.class);

        // With real OpenTelemetry tracing, we should get a real trace ID
        assertThat(response).matches("(?s).*\"traceId\":\"[a-f0-9]{32}\".*");
    }
}
