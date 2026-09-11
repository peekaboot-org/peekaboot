package org.peekaboot.autoconfigure.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.insights.InsightsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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

    /** The blob ToolbarShell embeds for toolbar.js, which reads it before it calls home. */
    private static final Pattern TOOLBAR_DATA =
            Pattern.compile("<script id=\"peekaboot-toolbar-data\" type=\"application/json\">(.*?)</script>");

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext context;

    private RestClient restClient;

    @BeforeEach
    void setUp() {
        restClient = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * Insights backs off without a MeterRegistry bean, and that bean comes from Boot's own
     * metrics auto-configuration - so the auto-configuration order has to put Peekaboot after
     * it, which nothing on this test classpath does by accident.
     */
    @Test
    void insightsServiceShouldBeCreatedAfterBootsMeterRegistry() {
        assertThat(context.getBeanNamesForType(InsightsService.class)).hasSize(1);
    }

    /**
     * The whole toolbar chain over HTTP: the filter injects the shell, the real Tracer named
     * the request's trace, and the data blob carries what the bar renders before it fetches
     * anything. Asserted on the parsed JSON rather than on substrings, so a blob that stopped
     * being valid JSON - the one thing toolbar.js cannot recover from - fails here.
     */
    @Test
    void toolbarShouldBeInjectedIntoHtmlResponseWithARealTraceId() {
        String response = restClient
                .get()
                .uri("/test")
                .accept(MediaType.TEXT_HTML)
                .retrieve()
                .body(String.class);

        assertThat(response).contains("<!-- Peekaboot Dev Toolbar -->");
        JsonNode data = toolbarData(response);
        assertThat(data.path("basePath").asString()).isEqualTo("/peekaboot");
        assertThat(data.path("method").asString()).isEqualTo("GET");
        assertThat(data.path("path").asString()).isEqualTo("/test");
        assertThat(data.path("status").asInt()).isEqualTo(200);
        assertThat(data.path("traceId").asString()).matches("[a-f0-9]{32}");
    }

    private static JsonNode toolbarData(String html) {
        Matcher matcher = TOOLBAR_DATA.matcher(html);
        assertThat(matcher.find())
                .as("the injected page carries the toolbar data blob")
                .isTrue();
        return JsonMapper.builder().build().readTree(matcher.group(1));
    }
}
