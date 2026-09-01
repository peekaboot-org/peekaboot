package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.testingapp.integration.ActuatorInsightsJson.findConfigInfoProperty;
import static org.peekaboot.testingapp.integration.ActuatorInsightsJson.findEnvironmentPropertyValue;

import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Proves the second half of the two-independent-opt-ins design end to end through the
 * real HTTP API: with {@code peekaboot.enable-unmasking=true}, the request's
 * {@code unmask=true} parameter now actually takes effect - but only when it is present.
 * See {@code UnmaskingDisabledIT} for the security-critical case where the
 * property is false.
 *
 * <p>A separate Spring context from {@code UnmaskingDisabledIT} is
 * unavoidable here: peekaboot.enable-unmasking is read once at context startup via
 * {@code PeekabootProperties}, so the two states can't share a context.
 *
 * <p>application-test.yml binds {@code spring.datasource.password} as a fixture value
 * purely to give this test a real, secret-looking property to check against.
 */
@SpringBootTest(
        classes = TestingApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "peekaboot.enable-unmasking=true")
@ActiveProfiles("test")
class UnmaskingEnabledIT {

    @LocalServerPort
    private int port;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private JsonNode getJson(String uri) {
        String body = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build()
                .get()
                .uri(uri)
                .retrieve()
                .body(String.class);
        return jsonMapper.readTree(body);
    }

    @Test
    void insightsEndpointReturnsRealValuesWhenUnmaskIsRequested() {
        JsonNode config =
                getJson("/peekaboot/api/actuator/all/insights?unmask=true").path("config");

        JsonNode passwordProperty = findConfigInfoProperty(config, "password");
        assertThat(passwordProperty.path("value").asString()).isEqualTo("test-fixture-password");
    }

    @Test
    void insightsEndpointAlsoUnmasksTheEnvironmentTab() {
        JsonNode environment =
                getJson("/peekaboot/api/actuator/all/insights?unmask=true").path("environment");

        JsonNode passwordValue = findEnvironmentPropertyValue(environment, "spring.datasource.password");
        assertThat(passwordValue.asString()).isEqualTo("test-fixture-password");
    }

    /**
     * Enabling the property is not, on its own, enough - the request still has to ask.
     * This is what stops the property from silently widening every existing request.
     */
    @Test
    void insightsEndpointStaysMaskedWhenUnmaskParameterIsNotSet() {
        JsonNode config = getJson("/peekaboot/api/actuator/all/insights").path("config");

        JsonNode passwordProperty = findConfigInfoProperty(config, "password");
        assertThat(passwordProperty.path("value").asString()).isEqualTo("******");
    }

    @Test
    void featuresReportsUnmaskingAsEnabled() {
        JsonNode features = getJson("/peekaboot/api/features");

        assertThat(features.path("unmaskingEnabled").asBoolean()).isTrue();
    }
}
