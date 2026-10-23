package org.peekaboot.autoconfigure.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

/**
 * With Peekaboot off, a consumer must serve nothing under /peekaboot: Boot's default static
 * resource handler would happily publish the dashboard bundle if it sat in one of the
 * default static locations of the frontend jar. Nor may anything of Peekaboot reach the
 * application's own pages: the switch is what a consumer reaches for to turn the whole thing
 * off, so a bar left rendering into every page would be the visible half of it failing.
 */
// No profile here, so this context disables Flyway and Liquibase itself. There are no migrations
// in this module; Flyway WARNs about the empty location and Liquibase fails on its default changelog.
@SpringBootTest(
        classes = TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"peekaboot.enabled=false", "spring.flyway.enabled=false", "spring.liquibase.enabled=false"})
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
        String page = new LocalServer(port).html("/test");

        assertThat(page).contains("Test Page").doesNotContain("Peekaboot", "peekaboot");
    }

    private HttpStatus statusOf(String path) {
        return HttpStatus.valueOf(new LocalServer(port)
                .client()
                .get()
                .uri(path)
                .retrieve()
                .toBodilessEntity()
                .getStatusCode()
                .value());
    }
}
