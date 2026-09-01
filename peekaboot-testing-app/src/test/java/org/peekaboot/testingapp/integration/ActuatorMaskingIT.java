package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.testingapp.integration.ActuatorInsightsJson.findConfigInfoProperty;
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
 * Proves masking end to end through the real HTTP API, not just at the mapper/
 * service unit level (see ConfigMapperTest, EnvironmentMapperTest, ActuatorResponseParserTest,
 * PeekabootActuatorServiceIT): a secret-looking property comes back masked from the
 * endpoint the dashboard reads.
 *
 * <p>application-test.yml binds {@code spring.datasource.password} as a fixture value
 * purely to give this test (and {@code DashboardTabsIT.configTabMasksSensitiveValues})
 * a real, secret-looking property to check the masking engine's actual output against -
 * the test profile's H2 datasource doesn't otherwise need a password.
 */
@SpringBootTest(classes = TestingApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnableConfigurationProperties(NestedConfigPropertiesFixture.class)
class ActuatorMaskingIT {

    @LocalServerPort
    private int port;

    private PeekabootApi api;

    @BeforeEach
    void connect() {
        api = new PeekabootApi(port);
    }

    @Test
    void insightsEndpointMasksASecretLookingConfigProperty() {
        JsonNode config = api.getJson("/peekaboot/api/actuator/all/insights").path("config");

        JsonNode passwordProperty = findConfigInfoProperty(config, "password");
        assertThat(passwordProperty)
                .as("the spring.datasource.password fixture property must be present in /configprops")
                .isNotNull();
        assertThat(passwordProperty.path("value").asString()).isEqualTo("******");
    }

    /**
     * The Environment tab renders raw property sources, so EnvironmentMapper needs masking
     * of its own - it is the most exposed surface, not merely one that has to stay
     * consistent with the Config tab.
     */
    @Test
    void insightsEndpointMasksTheSameSecretLookingPropertyInTheEnvironmentTab() {
        JsonNode environment =
                api.getJson("/peekaboot/api/actuator/all/insights").path("environment");

        JsonNode passwordValue = findEnvironmentPropertyValue(environment, "spring.datasource.password");
        assertThat(passwordValue)
                .as("spring.datasource.password must be present in some environment property source")
                .isNotNull();
        assertThat(passwordValue.asString()).isEqualTo("******");
    }

    /**
     * {@code ConfigMapper} masks inside a {@code @ConfigurationProperties} bean's nested
     * Map/List values before flattening them to text: flattened first with {@code
     * Object.toString()}, a sensitive key nested inside the tree (e.g. {@code
     * registration.google.client-secret}) would never reach {@code isSensitiveKey} - only
     * the flattened text would, and that text matches no value pattern. {@code
     * NestedConfigPropertiesFixture} provides that nesting.
     */
    @Test
    void insightsEndpointMasksASensitiveKeyNestedInsideAConfigurationPropertiesTree() {
        JsonNode config = api.getJson("/peekaboot/api/actuator/all/insights").path("config");

        JsonNode registrationProperty = findConfigInfoProperty(config, "registration");
        assertThat(registrationProperty)
                .as("the nested-fixture.registration property must be present in /configprops")
                .isNotNull();
        String value = registrationProperty.path("value").asString();
        assertThat(value).contains("client-secret=******");
        assertThat(value).doesNotContain("fixture-client-secret");
    }
}
