package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * The /raw endpoints (GET /api/actuator/all/raw, GET /api/traces/raw, GET
 * /api/traces/{traceId}/raw) had no caller anywhere and were deleted outright, not
 * deprecated. {@code PeekabootControllerTest.RemovedRawEndpoints} proves they are no
 * longer declared on the controller, but that is a Mockito unit test with no MockMvc -
 * it cannot show a route no longer resolves. This proves it at the real HTTP surface.
 */
@SpringBootTest(classes = TestingApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RemovedRawEndpointsIntegrationTest {

    @LocalServerPort
    private int port;

    private RestClient restClient() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void actuatorRawEndpointNoLongerExists() {
        assertThatThrownBy(() -> restClient()
                        .get()
                        .uri("/peekaboot/api/actuator/all/raw")
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    @Test
    void tracesRawEndpointNoLongerExists() {
        assertThatThrownBy(() -> restClient()
                        .get()
                        .uri("/peekaboot/api/traces/raw")
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    @Test
    void singleTraceRawEndpointNoLongerExists() {
        assertThatThrownBy(() -> restClient()
                        .get()
                        .uri("/peekaboot/api/traces/some-trace-id/raw")
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.NotFound.class);
    }
}
