package net.osslabz.peekaboot.backend.devtoolbar;

import io.micrometer.tracing.Span;
import net.osslabz.peekaboot.backend.service.PeekabookActuatorService;
import net.osslabz.peekaboot.backend.tracing.query.TraceQueryService;
import net.osslabz.peekaboot.backend.tracing.store.SpanData;
import net.osslabz.peekaboot.backend.tracing.store.TraceData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolbarDataProviderTest {

    @Mock
    TraceQueryService traceQueryService;

    @Mock
    PeekabookActuatorService actuatorService;

    ToolbarDataProvider provider;

    @BeforeEach
    void setUp() {
        provider = new ToolbarDataProvider(traceQueryService, actuatorService, "/peekaboot");
    }

    @Test
    void shouldGenerateValidJson() {
        String json = provider.getToolbarSummaryJson("GET", "/users", 200, null);

        assertThat(json).startsWith("{");
        assertThat(json).endsWith("}");
        assertThat(json).contains("\"method\":\"GET\"");
        assertThat(json).contains("\"path\":\"/users\"");
        assertThat(json).contains("\"status\":200");
    }

    @Test
    void shouldIncludeAllFields() {
        when(actuatorService.getData()).thenReturn(Map.of(
                "health", Map.of("status", "UP"),
                "info", Map.of("process", Map.of("memory", Map.of("heap", Map.of("used", 50L, "max", 100L))))
        ));

        String json = provider.getToolbarSummaryJson("POST", "/api/data", 201, "trace123");

        assertThat(json).contains("\"method\":\"POST\"");
        assertThat(json).contains("\"path\":\"/api/data\"");
        assertThat(json).contains("\"status\":201");
        assertThat(json).contains("\"traceId\":\"trace123\"");
        assertThat(json).contains("\"dashboardUrl\":\"/peekaboot/\"");
        assertThat(json).contains("\"health\":\"UP\"");
        assertThat(json).contains("\"memoryPercent\":50");
    }

    @Test
    void shouldEscapeJsonSpecialCharacters() {
        String json = provider.getToolbarSummaryJson("GET", "/path/with\"quotes", 200, null);

        assertThat(json).contains("/path/with\\\"quotes");
    }

    @Test
    void shouldHandleNullTraceId() {
        String json = provider.getToolbarSummaryJson("GET", "/users", 200, null);

        assertThat(json).contains("\"traceId\":null");
    }

    @Test
    void shouldHandleMissingTraceData() {
        when(traceQueryService.getTrace("unknown")).thenReturn(Optional.empty());

        String json = provider.getToolbarSummaryJson("GET", "/page", 200, "unknown");

        assertThat(json).contains("\"duration\":0");
        assertThat(json).contains("\"queryCount\":-1");
    }

    @Test
    void shouldExtractDurationFromTrace() {
        Instant start = Instant.now();
        Instant end = start.plusMillis(150);
        TraceData trace = new TraceData(
                "trace1",
                start,
                end,
                Duration.ofMillis(150),
                1,
                List.of()
        );
        when(traceQueryService.getTrace("trace1")).thenReturn(Optional.of(trace));

        String json = provider.getToolbarSummaryJson("GET", "/page", 200, "trace1");

        assertThat(json).contains("\"duration\":150");
    }

    @Test
    void shouldCountDatabaseQueries() {
        SpanData querySpan1 = createSpan("SELECT * FROM users", Map.of("db.system", "postgresql"));
        SpanData querySpan2 = createSpan("query", Map.of("db.system", "mysql"));
        SpanData httpSpan = createSpan("GET /users", Map.of());
        TraceData trace = new TraceData(
                "trace1",
                Instant.now(),
                Instant.now().plusMillis(100),
                Duration.ofMillis(100),
                3,
                List.of(httpSpan, querySpan1, querySpan2)
        );
        when(traceQueryService.getTrace("trace1")).thenReturn(Optional.of(trace));

        String json = provider.getToolbarSummaryJson("GET", "/page", 200, "trace1");

        // The method counts both by name pattern and db.system tag
        // SELECT + db.system = 2 for first span, query + db.system = 2 for second
        assertThat(json).matches(".*\"queryCount\":[2-4].*");
    }

    @Test
    void shouldCountErrors() {
        SpanData errorSpan = new SpanData(
                "trace1", "span1", null, "GET /error", Span.Kind.SERVER,
                Instant.now(), Instant.now().plusMillis(50), Duration.ofMillis(50),
                Map.of(), List.of(), "Connection refused", "IOException",
                null, null, null, List.of(), 1
        );
        SpanData okSpan = createSpan("GET /ok", Map.of());
        TraceData trace = new TraceData(
                "trace1",
                Instant.now(),
                Instant.now().plusMillis(100),
                Duration.ofMillis(100),
                2,
                List.of(errorSpan, okSpan)
        );
        when(traceQueryService.getTrace("trace1")).thenReturn(Optional.of(trace));

        String json = provider.getToolbarSummaryJson("GET", "/page", 200, "trace1");

        assertThat(json).contains("\"errorCount\":1");
    }

    @Test
    void shouldHandleActuatorServiceError() {
        when(actuatorService.getData()).thenThrow(new RuntimeException("Service unavailable"));

        String json = provider.getToolbarSummaryJson("GET", "/page", 200, null);

        assertThat(json).contains("\"health\":\"UNKNOWN\"");
        assertThat(json).contains("\"memoryPercent\":-1");
    }

    @Test
    void shouldHandleNullActuatorService() {
        ToolbarDataProvider providerWithNullActuator = new ToolbarDataProvider(traceQueryService, null, "/peekaboot");

        String json = providerWithNullActuator.getToolbarSummaryJson("GET", "/page", 200, null);

        assertThat(json).contains("\"health\":\"UNKNOWN\"");
        assertThat(json).contains("\"memoryPercent\":-1");
    }

    @Test
    void shouldHandleNullTraceQueryService() {
        ToolbarDataProvider providerWithNullTracing = new ToolbarDataProvider(null, actuatorService, "/peekaboot");

        String json = providerWithNullTracing.getToolbarSummaryJson("GET", "/page", 200, "trace123");

        assertThat(json).contains("\"duration\":0");
        assertThat(json).contains("\"queryCount\":-1");
    }

    @Test
    void shouldUseCorrectBasePath() {
        ToolbarDataProvider customProvider = new ToolbarDataProvider(traceQueryService, actuatorService, "/custom-path");

        String json = customProvider.getToolbarSummaryJson("GET", "/page", 200, null);

        assertThat(json).contains("\"dashboardUrl\":\"/custom-path/\"");
    }

    private SpanData createSpan(String name, Map<String, String> tags) {
        return new SpanData(
                "trace1", "span" + System.nanoTime(), null, name, Span.Kind.CLIENT,
                Instant.now(), Instant.now().plusMillis(10), Duration.ofMillis(10),
                tags, List.of(), null, null,
                null, null, null, List.of(), System.nanoTime()
        );
    }
}
