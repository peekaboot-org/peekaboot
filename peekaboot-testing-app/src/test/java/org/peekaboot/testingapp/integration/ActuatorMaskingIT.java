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
     * Map/List values and then flattens the tree to one dotted-key property per leaf:
     * masked first, a sensitive key nested inside the tree (e.g. {@code
     * registration.google.client-secret}) reaches {@code isSensitiveKey} as a real key
     * and arrives as its own masked property, filterable like any flat one. {@code
     * NestedConfigPropertiesFixture} provides that nesting.
     */
    @Test
    void insightsEndpointMasksASensitiveKeyNestedInsideAConfigurationPropertiesTree() {
        JsonNode config = api.getJson("/peekaboot/api/actuator/all/insights").path("config");

        JsonNode clientSecret = findConfigInfoProperty(config, "registration.google.client-secret");
        assertThat(clientSecret)
                .as("the nested fixture's client-secret must arrive as its own dotted-key property")
                .isNotNull();
        assertThat(clientSecret.path("value").asString()).isEqualTo("******");

        JsonNode clientId = findConfigInfoProperty(config, "registration.google.client-id");
        assertThat(clientId)
                .as("the innocuous sibling leaf keeps its value under its own dotted key")
                .isNotNull();
        assertThat(clientId.path("value").asString()).isEqualTo("fixture-client-id");
    }
}
