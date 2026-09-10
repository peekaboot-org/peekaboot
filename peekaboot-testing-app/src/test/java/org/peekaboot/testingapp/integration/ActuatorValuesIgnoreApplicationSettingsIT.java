package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.peekaboot.testingapp.integration.ActuatorInsightsJson.findConfigInfoProperty;
import static org.peekaboot.testingapp.integration.ActuatorInsightsJson.findEnvironmentPropertyValue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/**
 * The independence contract, end to end through the real HTTP API. Every actuator setting
 * that could decide what the dashboard sees is turned all the way down here: values hidden,
 * every endpoint excluded from web exposure, the env and configprops endpoints denied
 * outright. Health survives that exclusion only because
 * {@code PeekabootEndpointExposureOutcomeContributor} keeps its bean alive;
 * {@code theHealthTabCarriesTheComponentsAndTheirDetails} is what pins that end to end - the
 * exclusion would otherwise leave {@code healthInsightsSource}'s {@code HealthEndpoint} bean
 * absent.
 * Peekaboot builds those endpoints itself (see {@code ActuatorSourcesAutoConfiguration}), so
 * the dashboard reads the same data either way, and what it masks is {@code MaskingEngine}'s
 * decision alone - {@code server.port} readable, the datasource password not. Health is turned
 * down the same way: {@code show-details=never} is Spring's own default, and the setting under
 * which the dashboard's answer and the application's have to differ.
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
            "management.endpoint.configprops.access=none",
            "management.endpoint.health.show-details=never"
        })
@ActiveProfiles("test")
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

        JsonNode clientId = findConfigInfoProperty(config, "nested-fixture", "registration.google.client-id");
        assertThat(clientId)
                .as("the nested-fixture bean is bound in application-test.yml")
                .isNotNull();
        assertThat(clientId.path("value").asString()).isEqualTo("fixture-client-id");

        JsonNode clientSecret = findConfigInfoProperty(config, "nested-fixture", "registration.google.client-secret");
        assertThat(clientSecret)
                .as("the nested-fixture bean is bound in application-test.yml")
                .isNotNull();
        assertThat(clientSecret.path("value").asString()).isEqualTo("******");
    }

    /**
     * {@code PeekabootActuatorService} reads the {@code HealthEndpoint} bean directly rather
     * than its web response, so the components and their details reach the dashboard whatever
     * the application chose for {@code show-details} - the value set here is what a host that
     * never widened its own health endpoint has.
     */
    @Test
    void theHealthTabCarriesTheComponentsAndTheirDetails() {
        JsonNode health = api.getJson("/peekaboot/api/actuator/all/insights").path("health");

        assertThat(health.path("status").asString())
                .as("health must be readable even though every endpoint is excluded from web exposure")
                .isEqualTo("UP");
        JsonNode db = null;
        List<String> names = new ArrayList<>();
        for (JsonNode component : health.path("components")) {
            names.add(component.path("name").asString());
            if ("db".equals(component.path("name").asString())) {
                db = component;
            }
        }
        assertThat(names).contains("db", "diskSpace");
        assertThat(db).isNotNull();
        assertThat(db.path("status").asString()).isEqualTo("UP");
        assertThat(db.path("details").path("database").asString()).isEqualTo("H2");
    }

    /**
     * The contributor keeps the bean, not the mapping: {@code exclude=*} must still leave the
     * application's own {@code /actuator/health} unreachable in the very context whose
     * dashboard reads health above. Reachability is Boot's web filter's decision, and this is
     * what would catch a Boot change that let the contributor's match leak into it.
     */
    @Test
    void theApplicationsOwnHealthEndpointStaysOffTheWeb() {
        assertThatThrownBy(() -> api.restClient()
                        .get()
                        .uri("/actuator/health")
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.NotFound.class);
    }
}
