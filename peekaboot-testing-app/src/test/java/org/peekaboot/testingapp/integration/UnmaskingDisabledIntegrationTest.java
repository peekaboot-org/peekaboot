package org.peekaboot.testingapp.integration;

import org.peekaboot.testingapp.TestingApp;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.testingapp.integration.ActuatorInsightsJson.findConfigInfoProperty;
import static org.peekaboot.testingapp.integration.ActuatorInsightsJson.findRawConfigPropsProperty;

/**
 * Proves the security-critical half of the two-independent-opt-ins design end to end
 * through the real HTTP API: {@code peekaboot.enable-unmasking} is not set anywhere in
 * this context, so it defaults to false - and the request's {@code unmask=true} parameter
 * must never be a bypass on its own in that state. See
 * {@code UnmaskingEnabledIntegrationTest} for the complementary "both opt-ins present"
 * case, and {@code ActuatorMaskingIntegrationTest} for masking itself (not the opt-ins).
 *
 * <p>application-test.yml binds {@code spring.datasource.password} as a fixture value
 * purely to give this test a real, secret-looking property to check against.
 */
@SpringBootTest(classes = TestingApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UnmaskingDisabledIntegrationTest {

    @LocalServerPort
    private int port;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private JsonNode getJson(String uri) {
        String body = RestClient.builder().baseUrl("http://localhost:" + port).build()
                .get().uri(uri).retrieve().body(String.class);
        return jsonMapper.readTree(body);
    }

    /**
     * The single most important test in this feature: unmask=true must never be a bypass
     * on its own. Written first, and confirmed failing before peekaboot.enable-unmasking
     * and the unmask parameter existed.
     */
    @Test
    void insightsEndpointIgnoresUnmaskTrueWhenUnmaskingIsDisabled() {
        JsonNode config = getJson("/peekaboot/api/actuator/all/insights?unmask=true").path("config");

        JsonNode passwordProperty = findConfigInfoProperty(config, "password");
        assertThat(passwordProperty)
                .as("the spring.datasource.password fixture property must be present in /configprops")
                .isNotNull();
        assertThat(passwordProperty.path("value").asString()).isEqualTo("******");
    }

    /**
     * getRaw() is the broadest surface Peekaboot exposes and the one that already had a
     * masking bypass before this feature existed - it must resolve identically to the
     * insights endpoint above, not carry a bypass of its own.
     */
    @Test
    void rawEndpointIgnoresUnmaskTrueWhenUnmaskingIsDisabled() {
        JsonNode configprops = getJson("/peekaboot/api/actuator/all/raw?unmask=true")
                .path("spring").path("actuator").path("configprops");

        JsonNode passwordValue = findRawConfigPropsProperty(configprops, "password");
        assertThat(passwordValue)
                .as("the spring.datasource.password fixture property must be present in the raw payload")
                .isNotNull();
        assertThat(passwordValue.asString()).isEqualTo("******");
    }

    @Test
    void insightsEndpointStaysMaskedWithoutTheUnmaskParameter() {
        JsonNode config = getJson("/peekaboot/api/actuator/all/insights").path("config");

        JsonNode passwordProperty = findConfigInfoProperty(config, "password");
        assertThat(passwordProperty.path("value").asString()).isEqualTo("******");
    }

    @Test
    void featuresReportsUnmaskingAsDisabled() {
        JsonNode features = getJson("/peekaboot/api/features");

        assertThat(features.path("unmaskingEnabled").asBoolean()).isFalse();
    }
}
