package org.peekaboot.autoconfigure.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * With Peekaboot off, a consumer must serve nothing under /peekaboot: Boot's default static
 * resource handler would happily publish the dashboard bundle if it sat in one of the
 * default static locations of the frontend jar. Nor may anything of Peekaboot reach the
 * application's own pages: the switch is what a consumer reaches for to turn the whole thing
 * off, so a bar left rendering into every page would be the visible half of it failing.
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

    /** The entry point a reader bookmarks; with Peekaboot off there is nothing to redirect to. */
    @Test
    void theDashboardEntryPointDoesNotRedirect() {
        assertThat(statusOf("/peekaboot")).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf("/peekaboot/")).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theToolbarIsNotInjectedIntoTheApplicationsOwnPages() {
        String page = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build()
                .get()
                .uri("/test")
                .accept(MediaType.TEXT_HTML)
                .retrieve()
                .body(String.class);

        assertThat(page).contains("Test Page").doesNotContain("Peekaboot", "peekaboot");
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
