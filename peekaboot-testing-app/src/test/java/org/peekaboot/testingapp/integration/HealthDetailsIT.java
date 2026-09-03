package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The dashboard's health carries per-component detail without Peekaboot widening the
 * application's own public {@code /actuator/health}: {@code PeekabootActuatorService} reads
 * the {@code HealthEndpoint} bean directly, so {@code management.endpoint.health.show-details}
 * stays whatever the application chose. The test profile sets {@code always} for the other
 * tests; this context restates Spring's default, {@code never}, which is exactly the setting
 * under which the two answers must differ.
 */
@SpringBootTest(
        classes = TestingApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.endpoint.health.show-details=never")
@ActiveProfiles("test")
class HealthDetailsIT {

    @LocalServerPort
    private int port;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private String get(String uri) {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build()
                .get()
                .uri(uri)
                .retrieve()
                .body(String.class);
    }

    @Test
    void theDashboardsHealthCarriesTheComponentsAndTheirDetails() {
        JsonNode health =
                jsonMapper.readTree(get("/peekaboot/api/actuator/all/insights")).path("health");

        assertThat(health.path("status").asString()).isEqualTo("UP");
        List<String> names = new ArrayList<>();
        JsonNode db = null;
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

    /** Spring's default answer: the aggregate status and the group names, no components. */
    @Test
    void anAnonymousActuatorHealthAnswersWithTheAggregateStatusOnly() {
        JsonNode health = jsonMapper.readTree(get("/actuator/health"));

        assertThat(health.path("status").asString()).isEqualTo("UP");
        assertThat(health.propertyNames()).containsOnly("status", "groups");
    }
}
