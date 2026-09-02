package org.peekaboot.autoconfigure.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

/**
 * With Peekaboot off, a consumer must serve nothing under /peekaboot: Boot's default static
 * resource handler would happily publish the dashboard bundle if it sat in one of the
 * default static locations of the frontend jar.
 */
@SpringBootTest(
        classes = TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "peekaboot.enabled=false")
class PeekabootOffIT {

    @LocalServerPort
    private int port;

    @Test
    void dashboardAssetsAreNotServed() {
        assertThat(statusOf("/peekaboot/ui/dashboard/index.html")).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf("/peekaboot/ui/toolbar/toolbar.js")).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf("/peekaboot/api/features")).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private HttpStatus statusOf(String path) {
        return HttpStatus.valueOf(RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> {})
                .build()
                .get()
                .uri(path)
                .retrieve()
                .toBodilessEntity()
                .getStatusCode()
                .value());
    }
}
