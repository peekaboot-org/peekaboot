package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.peekaboot.backend.security.DashboardAuthenticationFilter;
import org.peekaboot.backend.security.DashboardCredentials;
import org.peekaboot.testingapp.TestingApp;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * No unit test can see this: with nothing else authenticating {@code /peekaboot/**}, a real
 * deployment launch arms Peekaboot's own fallback and really challenges the dashboard over
 * HTTP, and the generated password really survives a restart. {@code SecuredPeekabootIT}
 * covers the other half - the guard standing down once the application supplies its own
 * chain.
 *
 * <p>Runs under the {@code auto-security} profile because every other test in this module
 * excludes the servlet security auto-configuration - see application-test.yml.
 */
@SpringBootTest(classes = TestingApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("auto-security")
// Its own SpringApplicationBuilder boots skip spring-test's context-load lock; concurrent Logback resets throw
// ConcurrentModificationException.
@Isolated
class AutoSecuredDashboardIT {

    /** A resource-handler path, not a redirect: the one place an authenticated GET here answers 200 rather than 302. */
    private static final String DASHBOARD_ASSET = "/peekaboot/ui/dashboard/main.js";

    /** Same endpoint {@code SecuredPeekabootIT} uses, kept identical so the two ITs are not read as testing different APIs. */
    private static final String INSIGHTS_API = "/peekaboot/api/actuator/all/insights";

    @TempDir
    static Path credentialsDir;

    @LocalServerPort
    private int port;

    @Autowired
    private DashboardCredentials credentials;

    @DynamicPropertySource
    static void credentialsFile(DynamicPropertyRegistry registry) {
        registry.add(
                "peekaboot.security.credentials-file",
                () -> credentialsDir.resolve("security.properties").toString());
    }

    @Test
    void theDashboardEntryPointRejectsAnAnonymousRequest() {
        assertThat(api().statusOf("/peekaboot/")).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void theRefusalCarriesABasicAuthChallenge() {
        String challenge = api().headersOf("/peekaboot/").getFirst("WWW-Authenticate");

        assertThat(challenge).startsWith("Basic realm=\"Peekaboot\"");
    }

    @Test
    void theInsightsApiRejectsAnAnonymousRequest() {
        assertThat(api().statusOf(INSIGHTS_API)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * A fresh application, because the filter warns on its first challenge only and the shared
     * one may already have spent it. The capture is attached after the boot: Boot reinitialises
     * Logback on the first start after a context closed, which detaches an earlier capture.
     */
    @Test
    void warnsOnceThatNothingButTheGeneratedCredentialsProtectsTheDashboard(@TempDir Path dir) {
        try (ConfigurableApplicationContext app = boot(dir.resolve("security.properties"));
                LogCapture capture = LogCapture.attach(DashboardAuthenticationFilter.class)) {
            PeekabootApi freshApi = new PeekabootApi(
                    ((WebServerApplicationContext) app).getWebServer().getPort());

            freshApi.statusOf(INSIGHTS_API);
            freshApi.statusOf(INSIGHTS_API);

            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .startsWith("Challenged an unauthenticated request for /peekaboot/")
                        .contains("nothing but Peekaboot's own generated credentials");
            });
        }
    }

    @Test
    void servesTheDashboardWithTheGeneratedCredentials() {
        assertThat(api().withBasicAuth(credentials.username(), credentials.plaintext())
                        .statusOf(DASHBOARD_ASSET))
                .isEqualTo(HttpStatus.OK);
    }

    /** The application's own path stays reachable: the guard names only {@code /peekaboot/**}. */
    @Test
    void theApplicationsOwnPathsStayAnonymouslyReachable() {
        assertThat(api().statusOf("/persons")).isEqualTo(HttpStatus.OK);
    }

    @Test
    void persistsOnlyAHashOfTheGeneratedPassword() throws Exception {
        String content = Files.readString(credentialsDir.resolve("security.properties"));

        assertThat(content).contains("pbkdf2-sha256$").doesNotContain(credentials.plaintext());
    }

    /**
     * The central promise: a password generated on one run still opens the dashboard on a
     * genuinely new one. Boots two full applications in sequence against the same credentials
     * file, closing the first before the second starts, and carries only the plaintext password
     * across - everything else is read back from the second application's own context.
     */
    @Test
    void theGeneratedPasswordSurvivesARestart(@TempDir Path restartDir) {
        Path restartCredentialsFile = restartDir.resolve("security.properties");
        String username;
        String password;

        try (ConfigurableApplicationContext firstRun = boot(restartCredentialsFile)) {
            DashboardCredentials firstCredentials = firstRun.getBean(DashboardCredentials.class);
            assertThat(firstCredentials.origin()).isEqualTo(DashboardCredentials.Origin.GENERATED);
            username = firstCredentials.username();
            password = firstCredentials.plaintext();
        }

        try (ConfigurableApplicationContext secondRun = boot(restartCredentialsFile)) {
            DashboardCredentials secondCredentials = secondRun.getBean(DashboardCredentials.class);
            assertThat(secondCredentials.origin()).isEqualTo(DashboardCredentials.Origin.LOADED);
            assertThat(secondCredentials.username()).isEqualTo(username);
            assertThat(secondCredentials.plaintext()).isNull();

            int secondPort =
                    ((WebServerApplicationContext) secondRun).getWebServer().getPort();
            var response = RestClient.builder()
                    .baseUrl("http://localhost:" + secondPort)
                    .defaultStatusHandler(status -> true, (request, resp) -> {})
                    .build()
                    .get()
                    .uri(DASHBOARD_ASSET)
                    .headers(headers -> headers.setBasicAuth(username, password))
                    .retrieve()
                    .toBodilessEntity();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    private static ConfigurableApplicationContext boot(Path credentialsFile) {
        return new SpringApplicationBuilder(TestingApp.class)
                .profiles("auto-security")
                .run("--server.port=0", "--peekaboot.security.credentials-file=" + credentialsFile);
    }

    private PeekabootApi api() {
        return new PeekabootApi(port);
    }
}
