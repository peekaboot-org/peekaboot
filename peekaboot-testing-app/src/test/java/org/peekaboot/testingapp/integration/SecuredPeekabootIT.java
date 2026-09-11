package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.peekaboot.backend.security.DashboardCredentials;
import org.peekaboot.example.security.PeekabootSecurityConfig;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

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

    private PeekabootApi api;

    @BeforeEach
    void connect() {
        api = new PeekabootApi(port);
    }

    @Test
    void theDashboardEntryPointRejectsAnAnonymousRequest() {
        assertThat(api.statusOf("/peekaboot/")).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * The website tells readers that an unauthenticated browser gets the native credentials
     * prompt. That is a claim about the challenge header, not about the status code, so pin
     * the header rather than leaving the page asserting something nothing checks.
     */
    @Test
    void theRefusalCarriesABasicAuthChallenge() {
        String challenge = api.headersOf("/peekaboot/").getFirst("WWW-Authenticate");

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
        assertThat(api.statusOf("/peekaboot")).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void theInsightsApiRejectsAnAnonymousRequest() {
        assertThat(api.statusOf(INSIGHTS_API)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * The dashboard's own JavaScript is served by a resource handler rather than by
     * {@code PeekabootController}, so it exercises a different part of the mapping than
     * every other case here.
     */
    @Test
    void aDashboardStaticAssetRejectsAnAnonymousRequest() {
        assertThat(api.statusOf(DASHBOARD_ASSET)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anAuthenticatedUserWithoutTheAdminRoleIsForbidden() {
        assertThat(api.withBasicAuth("user", "user-password").statusOf(INSIGHTS_API))
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** getJson accepts nothing but a 2xx, so the parse is the status assertion too. */
    @Test
    void anAdminReceivesTheRealInsightsPayload() {
        JsonNode insights = api.withBasicAuth("admin", "admin-password").getJson(INSIGHTS_API);

        assertThat(insights.has("config"))
                .as("an admin must get the real insights payload, not an error page")
                .isTrue();
    }

    @Test
    void anAdminCanFetchADashboardStaticAsset() {
        assertThat(api.withBasicAuth("admin", "admin-password").statusOf(DASHBOARD_ASSET))
                .isEqualTo(HttpStatus.OK);
    }

    /**
     * The Peekaboot chain is ordered ahead of the application's own. This pins that it
     * takes only the paths its {@code securityMatcher} names and leaves the rest to the
     * catch-all chain, rather than swallowing the whole application.
     */
    @Test
    void theApplicationsOwnPathsStayAnonymouslyReachable() {
        assertThat(api.statusOf("/persons")).isEqualTo(HttpStatus.OK);
    }

    /**
     * The application's chain already authenticates /peekaboot/** so the guard stands down and
     * the application's own admin credentials keep working - Peekaboot's generated ones are
     * never required. If the guard were absent this class would fail to autowire
     * {@link DashboardCredentials} rather than pass by accident.
     */
    @Nested
    @SpringBootTest(
            classes = {TestingApp.class, PeekabootSecurityConfig.class},
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "peekaboot.security.enabled=true")
    @ActiveProfiles("security")
    class WithPeekabootsOwnGuardArmed {

        @TempDir
        static Path credentialsDir;

        @LocalServerPort
        private int nestedPort;

        @Autowired
        private DashboardCredentials credentials;

        private PeekabootApi nestedApi;

        @DynamicPropertySource
        static void credentialsFile(DynamicPropertyRegistry registry) {
            registry.add(
                    "peekaboot.security.credentials-file",
                    () -> credentialsDir.resolve("security.properties").toString());
        }

        @BeforeEach
        void connect() {
            nestedApi = new PeekabootApi(nestedPort);
        }

        @Test
        void theApplicationsCredentialsStillOpenTheDashboard() {
            assertThat(nestedApi.withBasicAuth("admin", "admin-password").statusOf(DASHBOARD_ASSET))
                    .isEqualTo(HttpStatus.OK);
        }

        /** Proves the application's chain is what gates the dashboard, not a Peekaboot challenge standing in for it. */
        @Test
        void peekabootsOwnGeneratedCredentialsAreNeverRequired() {
            assertThat(nestedApi
                            .withBasicAuth(credentials.username(), credentials.plaintext())
                            .statusOf(DASHBOARD_ASSET))
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
}
