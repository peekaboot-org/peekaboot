package org.peekaboot.autoconfigure.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** With Peekaboot on, the application's own /actuator/info is what it is with Peekaboot off. */
class HostActuatorInfoIT {

    @Test
    void theApplicationsActuatorInfoIsTheSameWithPeekabootOnAndOff() {
        JsonNode off = actuatorInfo(false);
        JsonNode on = actuatorInfo(true);

        assertThat(off.path("build").path("artifact").asString()).isEqualTo("lifecycle-test-app");
        assertThat(on).isEqualTo(off);
    }

    /** Command-line arguments, so the switch outranks application-integration.yml. */
    private static JsonNode actuatorInfo(boolean peekabootEnabled) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(TestApplication.class)
                .profiles("integration")
                .run(
                        "--server.port=0",
                        "--management.endpoints.web.exposure.include=info",
                        "--spring.info.build.location=classpath:test-build-info.properties",
                        "--info.app.name=demo",
                        "--peekaboot.enabled=" + peekabootEnabled)) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            ResponseEntity<String> response = new LocalServer(port)
                    .client()
                    .get()
                    .uri("/actuator/info")
                    .retrieve()
                    .toEntity(String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            return JsonMapper.builder().build().readTree(response.getBody());
        }
    }
}
