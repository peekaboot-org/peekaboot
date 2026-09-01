package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.peekaboot.example.security.PeekabootSecurityConfig;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Peekaboot ships no authentication of its own, so the website's security page tells
 * readers to put a Spring Security filter chain in front of {@code /peekaboot/**}. This
 * proves that the configuration it publishes - {@link PeekabootSecurityConfig}, imported
 * here verbatim - actually does what the page claims, at the real HTTP surface.
 *
 * <p>The three cases that carry the weight are the static asset (a resource-handler path,
 * not a controller mapping, and so the one most easily left open by a matcher that only
 * covers the API), the extensionless {@code /peekaboot} redirect, and the authenticated
 * non-admin: without that last one, every assertion here would still pass for a chain that
 * merely required a login rather than the role the page tells readers to gate on.
 *
 * <p>Runs under the {@code security} profile because every other test in this module
 * excludes the servlet security auto-configuration - see application-test.yml.
 */
@SpringBootTest(
        classes = {TestingApp.class, PeekabootSecurityConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("security")
class SecuredPeekabootIT {

    private static final String INSIGHTS_API = "/peekaboot/api/actuator/all/insights";
    private static final String DASHBOARD_ASSET = "/peekaboot/ui/dashboard/main.js";

    @LocalServerPort
    private int port;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void theDashboardEntryPointRejectsAnAnonymousRequest() {
        assertThatThrownBy(() -> anonymous().get().uri("/peekaboot/").retrieve().toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.Unauthorized.class);
    }

    /**
     * The website tells readers that an unauthenticated browser gets the native credentials
     * prompt. That is a claim about the challenge header, not about the status code, so pin
     * the header rather than leaving the page asserting something nothing checks.
     */
    @Test
    void theRefusalCarriesABasicAuthChallenge() {
        String challenge = anonymous()
                .get()
                .uri("/peekaboot/")
                .exchange((request, response) -> response.getHeaders().getFirst("WWW-Authenticate"));

        assertThat(challenge).startsWith("Basic");
    }

    /**
     * {@code /peekaboot} without the trailing slash is a redirect view controller of its
     * own (see PeekabootWebConfig), so it is a genuinely separate path from
     * {@code /peekaboot/} - and a {@code /peekaboot/**} matcher that failed to cover it
     * would leave a reachable entry point behind.
     */
    @Test
    void theExtensionlessDashboardPathRejectsAnAnonymousRequest() {
        assertThatThrownBy(() -> anonymous().get().uri("/peekaboot").retrieve().toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.Unauthorized.class);
    }

    @Test
    void theInsightsApiRejectsAnAnonymousRequest() {
        assertThatThrownBy(() -> anonymous().get().uri(INSIGHTS_API).retrieve().toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.Unauthorized.class);
    }

    /**
     * The dashboard's own JavaScript is served by a resource handler rather than by
     * {@code PeekabootController}, so it exercises a different part of the mapping than
     * every other case here.
     */
    @Test
    void aDashboardStaticAssetRejectsAnAnonymousRequest() {
        assertThatThrownBy(
                        () -> anonymous().get().uri(DASHBOARD_ASSET).retrieve().toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.Unauthorized.class);
    }

    @Test
    void anAuthenticatedUserWithoutTheAdminRoleIsForbidden() {
        assertThatThrownBy(() -> authenticatedAs("user", "user-password")
                        .get()
                        .uri(INSIGHTS_API)
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    @Test
    void anAdminReceivesTheRealInsightsPayload() {
        ResponseEntity<String> response = authenticatedAs("admin", "admin-password")
                .get()
                .uri(INSIGHTS_API)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode insights = jsonMapper.readTree(response.getBody());
        assertThat(insights.has("config"))
                .as("an admin must get the real insights payload, not an error page")
                .isTrue();
    }

    @Test
    void anAdminCanFetchADashboardStaticAsset() {
        ResponseEntity<String> response = authenticatedAs("admin", "admin-password")
                .get()
                .uri(DASHBOARD_ASSET)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * The Peekaboot chain is ordered ahead of the application's own. This pins that it
     * takes only the paths its {@code securityMatcher} names and leaves the rest to the
     * catch-all chain, rather than swallowing the whole application.
     */
    @Test
    void theApplicationsOwnPathsStayAnonymouslyReachable() {
        ResponseEntity<String> response =
                anonymous().get().uri("/persons").retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private RestClient anonymous() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private RestClient authenticatedAs(String username, String password) {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeaders(headers -> headers.setBasicAuth(username, password))
                .build();
    }
}
