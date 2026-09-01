package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * The response headers Peekaboot's own API carries through the real, auto-configured
 * app: JSON holding environment variables and captured headers must not be stored by
 * a proxy or the back-forward cache, nor content-sniffed (ApiSecurityHeadersInterceptor,
 * registered by PeekabootWebConfig for /peekaboot/api/** only - the dashboard's static
 * assets keep their own, revalidating cache policy).
 */
@SpringBootTest(classes = TestingApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApiHeadersIT {

    @LocalServerPort
    private int port;

    private HttpHeaders headersOf(String path) {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build()
                .get()
                .uri(path)
                .retrieve()
                .toBodilessEntity()
                .getHeaders();
    }

    @Test
    void apiResponsesAreNeitherStoredNorSniffed() {
        HttpHeaders headers = headersOf("/peekaboot/api/insights/config");

        assertThat(headers.getCacheControl()).isEqualTo("no-store");
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void dashboardAssetsKeepTheirRevalidatingCachePolicy() {
        HttpHeaders headers = headersOf("/peekaboot/ui/dashboard/index.html");

        assertThat(headers.getCacheControl()).isEqualTo("no-cache");
    }
}
