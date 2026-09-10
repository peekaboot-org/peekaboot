package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

/**
 * What Peekaboot serves under {@code /peekaboot} through the real, auto-configured app, and
 * on what terms: JSON holding environment variables and captured headers must not be stored by
 * a proxy or the back-forward cache, nor content-sniffed (ApiSecurityHeadersInterceptor,
 * registered by PeekabootWebConfig for /peekaboot/api/** only - the dashboard's static
 * assets keep their own, revalidating cache policy), and the assets themselves have to arrive
 * at all.
 */
@SpringBootTest(classes = TestingApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApiHeadersIT {

    @LocalServerPort
    private int port;

    private PeekabootApi api;

    @BeforeEach
    void connect() {
        api = new PeekabootApi(port);
    }

    @Test
    void apiResponsesAreNeitherStoredNorSniffed() {
        HttpHeaders headers = api.headersOf("/peekaboot/api/insights/config");

        assertThat(headers.getCacheControl()).isEqualTo("no-store");
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void dashboardAssetsKeepTheirRevalidatingCachePolicy() {
        HttpHeaders headers = api.headersOf("/peekaboot/ui/dashboard/index.html");

        assertThat(headers.getCacheControl()).isEqualTo("no-cache");
    }

    /**
     * The icon set is referenced only from CSS {@code url()} and {@code <link rel="icon">}, so a
     * path typo or a packaging change that stopped shipping binaries from the frontend module
     * would fail silently - no console error a UI test would notice, just a missing favicon and
     * an empty logo box.
     */
    @Test
    void iconAssetsAreServed() {
        for (String asset : List.of("favicon-16.png", "favicon-32.png", "logo-mark.png", "logo-mark-dark.png")) {
            assertThat(api.statusOf("/peekaboot/ui/assets/" + asset)).as(asset).isEqualTo(HttpStatus.OK);
        }
    }
}
