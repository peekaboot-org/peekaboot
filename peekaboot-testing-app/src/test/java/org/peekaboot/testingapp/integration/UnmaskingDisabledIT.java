package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.testingapp.integration.ActuatorInsightsJson.findConfigInfoProperty;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Proves the security-critical half of the two-independent-opt-ins design end to end
 * through the real HTTP API: application.yml switches {@code peekaboot.unmasking.enabled} on
 * for the demo and application-test.yml switches it off again, so this context runs with it
 * false - and the request's {@code unmask=true} parameter
 * must never be a bypass on its own in that state. See
 * {@code UnmaskingEnabledIT} for the complementary "both opt-ins present"
 * case, and {@code ActuatorMaskingIT} for masking itself (not the opt-ins).
 *
 * <p>application-test.yml binds {@code spring.datasource.password} as a fixture value
 * purely to give this test a real, secret-looking property to check against.
 */
class UnmaskingDisabledIT extends TestingAppITBase {

    /**
     * The single most important test in this feature: unmask=true must never be a bypass
     * on its own.
     */
    @Test
    void insightsEndpointIgnoresUnmaskTrueWhenUnmaskingIsDisabled() {
        JsonNode config =
                api.getJson("/peekaboot/api/actuator/all/insights?unmask=true").path("config");

        JsonNode passwordProperty = findConfigInfoProperty(config, "spring.datasource", "password");
        assertThat(passwordProperty)
                .as("the spring.datasource.password fixture property must be present in /configprops")
                .isNotNull();
        assertThat(passwordProperty.path("value").asString()).isEqualTo("******");
    }

    @Test
    void featuresReportsUnmaskingAsDisabled() {
        JsonNode features = api.getJson("/peekaboot/api/features");

        assertThat(features.path("unmaskingEnabled").asBoolean()).isFalse();
    }
}
