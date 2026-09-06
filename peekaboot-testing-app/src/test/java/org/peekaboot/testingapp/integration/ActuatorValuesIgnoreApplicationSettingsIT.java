package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.testingapp.integration.ActuatorInsightsJson.findEnvironmentPropertyValue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/**
 * The independence contract, end to end through the real HTTP API. Every actuator setting
 * that could decide what the dashboard sees is turned all the way down here: values hidden,
 * every endpoint excluded from web exposure, the env and configprops endpoints denied
 * outright. Health survives that exclusion only because
 * {@code PeekabootEndpointExposureOutcomeContributor} keeps its bean alive, which this test
 * exercises in passing.
 * Peekaboot builds those endpoints itself (see {@code ActuatorSourcesAutoConfiguration}), so
 * the dashboard reads the same data either way, and what it masks is {@code MaskingEngine}'s
 * decision alone - {@code server.port} readable, the datasource password not.
 *
 * <p>A separate Spring context from the rest of this package's masking tests, which is what
 * the property overrides here buy; {@code ActuatorMaskingIT} covers the same masking rules
 * against a default actuator configuration.
 */
@SpringBootTest(
        classes = TestingApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.endpoint.env.show-values=never",
            "management.endpoint.configprops.show-values=never",
            "management.endpoints.web.exposure.exclude=*",
            "management.endpoint.env.access=none",
            "management.endpoint.configprops.access=none"
        })
@ActiveProfiles("test")
@EnableConfigurationProperties(NestedConfigPropertiesFixture.class)
class ActuatorValuesIgnoreApplicationSettingsIT {

    @LocalServerPort
    private int port;

    private PeekabootApi api;

    @BeforeEach
    void connect() {
        api = new PeekabootApi(port);
    }

    @Test
    void theEnvironmentTabShowsANonSensitiveValueTheApplicationHidesFromItsOwnActuator() {
        JsonNode environment =
                api.getJson("/peekaboot/api/actuator/all/insights").path("environment");

        JsonNode portValue = findEnvironmentPropertyValue(environment, "server.port");
        assertThat(portValue)
                .as("server.port must be present in some environment property source")
                .isNotNull();
        assertThat(portValue.asString()).isNotEqualTo("******").isNotBlank();
    }

    @Test
    void theEnvironmentTabStillMasksASecret() {
        JsonNode environment =
                api.getJson("/peekaboot/api/actuator/all/insights").path("environment");

        JsonNode password = findEnvironmentPropertyValue(environment, "spring.datasource.password");
        assertThat(password)
                .as("spring.datasource.password is bound in application-test.yml")
                .isNotNull();
        assertThat(password.asString()).isEqualTo("******");
    }

    @Test
    void theConfigTabShowsANonSensitiveValueAndStillMasksItsSensitiveSibling() {
        JsonNode config = api.getJson("/peekaboot/api/actuator/all/insights").path("config");

        JsonNode clientId =
                ActuatorInsightsJson.findConfigInfoProperty(config, "nested-fixture", "registration.google.client-id");
        assertThat(clientId)
                .as("the nested-fixture bean is bound in application-test.yml")
                .isNotNull();
        assertThat(clientId.path("value").asString()).isEqualTo("fixture-client-id");

        JsonNode clientSecret = ActuatorInsightsJson.findConfigInfoProperty(
                config, "nested-fixture", "registration.google.client-secret");
        assertThat(clientSecret)
                .as("the nested-fixture bean is bound in application-test.yml")
                .isNotNull();
        assertThat(clientSecret.path("value").asString()).isEqualTo("******");
    }
}
