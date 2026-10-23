package org.peekaboot.backend.devtoolbar;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.config.PeekabootJson;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import tools.jackson.databind.JsonNode;

class ToolbarDataProviderTest {

    PeekabootTracingProperties tracingProperties;
    ToolbarDataProvider provider;

    @BeforeEach
    void setUp() {
        tracingProperties = new PeekabootTracingProperties();
        provider = new ToolbarDataProvider(tracingProperties);
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

    /** The overlay the bar opens colours by these, so they have to be the configured ones, in both modes. */
    @Test
    void shouldCarryTheEffectiveThresholds() {
        tracingProperties.setSlowSpanThresholdMs(250);
        tracingProperties.setVerySlowSpanThresholdMs(900);
        tracingProperties.setSlowQueryThresholdMs(75);

        for (String json : new String[] {
            provider.getToolbarSummaryJson("/peekaboot", "GET", "/users", 200, null),
            provider.getIdleModeJson("/peekaboot")
        }) {
            JsonNode features = PeekabootJson.MAPPER.readTree(json).path("features");
            assertThat(features.path("slowSpanThresholdMs").asLong()).isEqualTo(250L);
            assertThat(features.path("verySlowSpanThresholdMs").asLong()).isEqualTo(900L);
            assertThat(features.path("slowQueryThresholdMs").asLong()).isEqualTo(75L);
        }
    }
}
