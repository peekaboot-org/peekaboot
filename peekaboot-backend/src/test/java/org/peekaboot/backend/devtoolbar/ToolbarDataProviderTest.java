package org.peekaboot.backend.devtoolbar;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.config.PeekabootJson;
import tools.jackson.databind.JsonNode;

class ToolbarDataProviderTest {

    ToolbarDataProvider provider;

    @BeforeEach
    void setUp() {
        provider = new ToolbarDataProvider();
    }

    @Test
    void shouldGenerateValidJson() {
        String json = provider.getToolbarSummaryJson("/peekaboot", "POST", "/api/data", 201, "trace123");

        JsonNode parsed = PeekabootJson.MAPPER.readTree(json);
        assertThat(parsed.path("basePath").asString()).isEqualTo("/peekaboot");
        assertThat(parsed.path("method").asString()).isEqualTo("POST");
        assertThat(parsed.path("path").asString()).isEqualTo("/api/data");
        assertThat(parsed.path("status").asInt()).isEqualTo(201);
        assertThat(parsed.path("traceId").asString()).isEqualTo("trace123");
    }

    /** A request served outside any span (tracing off, an excluded path) still gets a bar; the key is there, null. */
    @Test
    void shouldCarryAMissingTraceIdAsJsonNull() {
        String json = provider.getToolbarSummaryJson("/peekaboot", "GET", "/users", 200, null);

        JsonNode parsed = PeekabootJson.MAPPER.readTree(json);
        assertThat(parsed.has("traceId")).isTrue();
        assertThat(parsed.path("traceId").isNull()).isTrue();
    }

    @Test
    void shouldEscapeAngleBracketsToPreventScriptTagBreakout() {
        // The JSON is embedded verbatim inside a <script> tag; a literal
        // </script> in the payload would terminate the tag and inject markup.
        String json = provider.getToolbarSummaryJson(
                "/peekaboot", "GET", "/foo</script><script>alert(1)</script>", 200, null);

        assertThat(json).doesNotContainIgnoringCase("</script>");
        assertThat(json).contains("\\u003c/script\\u003e");
    }

    /** The base path is whatever the filter resolved for this request - context path included. */
    @Test
    void shouldCarryTheBasePathItIsGiven() {
        assertThat(provider.getToolbarSummaryJson("/app/peekaboot", "GET", "/app/users", 200, null))
                .contains("\"basePath\":\"/app/peekaboot\"");
        assertThat(provider.getIdleModeJson("/app/peekaboot")).contains("\"basePath\":\"/app/peekaboot\"");
    }

    @Test
    void shouldGenerateIdleModeJson() {
        JsonNode parsed = PeekabootJson.MAPPER.readTree(provider.getIdleModeJson("/peekaboot"));

        assertThat(parsed.path("idle").asBoolean()).isTrue();
        assertThat(parsed.path("basePath").asString()).isEqualTo("/peekaboot");
    }
}
