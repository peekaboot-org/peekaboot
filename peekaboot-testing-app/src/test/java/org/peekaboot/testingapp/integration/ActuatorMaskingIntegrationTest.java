package org.peekaboot.testingapp.integration;

import org.peekaboot.testingapp.TestingApp;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.testingapp.integration.ActuatorInsightsJson.findConfigInfoProperty;
import static org.peekaboot.testingapp.integration.ActuatorInsightsJson.findEnvironmentPropertyValue;
import static org.peekaboot.testingapp.integration.ActuatorInsightsJson.findRawConfigPropsProperty;

/**
 * Proves Defect 1's fix end-to-end through the real HTTP API, not just at the mapper/
 * service unit level (see ConfigMapperTest, EnvironmentMapperTest, ActuatorRawMapperTest,
 * PeekabootActuatorServiceTest): a secret-looking property comes back masked from both
 * the endpoint the dashboard reads and the one that historically bypassed every mapper's
 * masking.
 *
 * <p>application-test.yml binds {@code spring.datasource.password} as a fixture value
 * purely to give this test (and {@code DashboardTabsTest.configTabMasksSensitiveValues})
 * a real, secret-looking property to check the masking engine's actual output against -
 * the test profile's H2 datasource doesn't otherwise need a password.
 */
@SpringBootTest(classes = TestingApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnableConfigurationProperties(NestedConfigPropertiesFixture.class)
class ActuatorMaskingIntegrationTest {

    @LocalServerPort
    private int port;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private JsonNode getJson(String uri) {
        String body = RestClient.builder().baseUrl("http://localhost:" + port).build()
                .get().uri(uri).retrieve().body(String.class);
        return jsonMapper.readTree(body);
    }

    @Test
    void insightsEndpointMasksASecretLookingConfigProperty() {
        JsonNode config = getJson("/peekaboot/api/actuator/all/insights").path("config");

        JsonNode passwordProperty = findConfigInfoProperty(config, "password");
        assertThat(passwordProperty)
                .as("the spring.datasource.password fixture property must be present in /configprops")
                .isNotNull();
        assertThat(passwordProperty.path("value").asString()).isEqualTo("******");
    }

    /**
     * EnvironmentMapper had no masking of its own before this - the Environment tab was
     * the most exposed surface of Defect 1, not merely an inconsistent one.
     */
    @Test
    void insightsEndpointMasksTheSameSecretLookingPropertyInTheEnvironmentTab() {
        JsonNode environment = getJson("/peekaboot/api/actuator/all/insights").path("environment");

        JsonNode passwordValue = findEnvironmentPropertyValue(environment, "spring.datasource.password");
        assertThat(passwordValue)
                .as("spring.datasource.password must be present in some environment property source")
                .isNotNull();
        assertThat(passwordValue.asString()).isEqualTo("******");
    }

    /**
     * Known Defect C1: {@code ConfigMapper} used to flatten a {@code @ConfigurationProperties}
     * bean's nested Map/List values to a string with {@code Object.toString()} before masking,
     * so a sensitive key nested inside the tree (e.g. {@code registration.google.client-secret})
     * never reached {@code isSensitiveKey} - only the flattened text did, and that text matches
     * no value pattern. {@code NestedConfigPropertiesFixture} reproduces that nesting.
     */
    @Test
    void insightsEndpointMasksASensitiveKeyNestedInsideAConfigurationPropertiesTree() {
        JsonNode config = getJson("/peekaboot/api/actuator/all/insights").path("config");

        JsonNode registrationProperty = findConfigInfoProperty(config, "registration");
        assertThat(registrationProperty)
                .as("the nested-fixture.registration property must be present in /configprops")
                .isNotNull();
        String value = registrationProperty.path("value").asString();
        assertThat(value).contains("client-secret=******");
        assertThat(value).doesNotContain("fixture-client-secret");
    }

    /**
     * GET /peekaboot/api/actuator/all/raw historically returned PeekabootActuatorService's
     * raw payload completely unmasked, bypassing every one of the typed mappers the
     * insights endpoint routes through. This is the one test proving that hole is closed.
     */
    @Test
    void rawEndpointMasksTheSamePropertyInsteadOfBypassingEveryMapper() {
        JsonNode raw = getJson("/peekaboot/api/actuator/all/raw").path("spring").path("actuator");

        JsonNode configprops = raw.path("configprops");
        JsonNode passwordValue = findRawConfigPropsProperty(configprops, "password");
        assertThat(passwordValue)
                .as("the spring.datasource.password fixture property must be present in the raw payload")
                .isNotNull();
        assertThat(passwordValue.asString()).isEqualTo("******");
    }
}
