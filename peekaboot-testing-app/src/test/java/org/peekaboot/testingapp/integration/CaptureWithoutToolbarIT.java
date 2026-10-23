package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.peekaboot.backend.devtoolbar.ToolbarDataProvider;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * Request and log capture feed the trace store the dashboard reads, so they follow tracing and
 * not the toolbar switch: with the toolbar off, a trace still carries its request and its logs.
 */
@SpringBootTest(
        classes = TestingApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "peekaboot.dev-toolbar.enabled=false")
class CaptureWithoutToolbarIT extends TestingAppITBase {

    @Autowired
    private ApplicationContext context;

    @Test
    void aTraceCarriesItsRequestAndLogsWithTheToolbarOff() {
        assertThat(context.getBeanNamesForType(ToolbarDataProvider.class)).isEmpty();

        JsonNode trace = traces.awaitTrace(traces.get("/?error=true"), TraceApiClient.ROOT_SPAN_EXPORTED);

        JsonNode request = trace.path("httpExchange").path("request");
        assertThat(request.path("method").asString()).isEqualTo("GET");
        assertThat(request.path("path").asString()).isEqualTo("/");
        assertThat(trace.path("logs").valueStream().map(log -> log.path("level").asString()))
                .contains("ERROR");
    }
}
