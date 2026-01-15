package net.osslabz.peekaboot.backend.devtoolbar;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolbarDataProviderTest {

    ToolbarDataProvider provider;

    @BeforeEach
    void setUp() {
        provider = new ToolbarDataProvider("/peekaboot");
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
        String json = provider.getToolbarSummaryJson("POST", "/api/data", 201, "trace123");

        assertThat(json).contains("\"method\":\"POST\"");
        assertThat(json).contains("\"path\":\"/api/data\"");
        assertThat(json).contains("\"status\":201");
        assertThat(json).contains("\"traceId\":\"trace123\"");
        assertThat(json).contains("\"basePath\":\"/peekaboot\"");
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
    void shouldUseCorrectBasePath() {
        ToolbarDataProvider customProvider = new ToolbarDataProvider("/custom-path");

        String json = customProvider.getToolbarSummaryJson("GET", "/page", 200, null);

        assertThat(json).contains("\"basePath\":\"/custom-path\"");
    }

    @Test
    void shouldEscapeBackslashInPath() {
        String json = provider.getToolbarSummaryJson("GET", "/path\\with\\backslashes", 200, null);

        assertThat(json).contains("/path\\\\with\\\\backslashes");
    }

    @Test
    void shouldEscapeNewlinesInPath() {
        String json = provider.getToolbarSummaryJson("GET", "/path\nwith\nnewlines", 200, null);

        assertThat(json).contains("/path\\nwith\\nnewlines");
    }

    @Test
    void shouldEscapeTabsInTraceId() {
        String json = provider.getToolbarSummaryJson("GET", "/page", 200, "trace\twith\ttabs");

        assertThat(json).contains("trace\\twith\\ttabs");
    }
}
