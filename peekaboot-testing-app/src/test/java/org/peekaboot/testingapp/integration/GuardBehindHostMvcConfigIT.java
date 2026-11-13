package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.peekaboot.backend.security.DashboardCredentials;
import org.peekaboot.fixtures.CaseInsensitivePathsConfig;
import org.peekaboot.fixtures.PrefixedRestControllersConfig;
import org.peekaboot.fixtures.SecondDispatcherServletConfig;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;

/**
 * A host's own Spring MVC configuration can serve Peekaboot's handlers at a path the guard's
 * servlet filter is not mapped to. Only a real container shows it: the filter mapping is the
 * container's, the handler mapping is Spring MVC's, and the two have to disagree for a request to
 * slip between them.
 */
// Its own SpringApplicationBuilder boots skip spring-test's context-load lock, like AutoSecuredDashboardIT's.
@Isolated
class GuardBehindHostMvcConfigIT {

    private static final String FEATURES_API = "/peekaboot/api/features";

    private static final String DASHBOARD_ASSET = "/peekaboot/ui/dashboard/main.js";

    @TempDir
    Path credentialsDir;

    @Test
    void challengesTheApiBehindAPathPrefix() {
        try (ConfigurableApplicationContext app = boot(PrefixedRestControllersConfig.class)) {
            PeekabootApi anonymous = apiOf(app);
            PeekabootApi signedIn = signedIn(app);
            String path = PrefixedRestControllersConfig.PREFIX + FEATURES_API;

            assertThat(anonymous.statusOf(path)).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(anonymous.headersOf(path).getFirst("WWW-Authenticate")).startsWith("Basic realm=\"Peekaboot\"");
            assertThat(anonymous.statusOf(path + "?nope=1"))
                    .as("the challenge comes before the API says which parameters it takes")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(signedIn.statusOf(path)).isEqualTo(HttpStatus.OK);
            assertThat(signedIn.headersOf(path).getCacheControl()).isEqualTo("no-store");
            assertThat(signedIn.statusOf(path + "?nope=1")).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void challengesTheApiUnderACaseInsensitiveMatcher() {
        try (ConfigurableApplicationContext app = boot(CaseInsensitivePathsConfig.class)) {
            assertChallengedUntilSignedIn(app, "/PEEKABOOT/api/features");
            assertChallengedUntilSignedIn(app, "/PEEKABOOT/ui/dashboard/main.js");
        }
    }

    @Test
    void challengesTheApiBehindASecondDispatcherServlet() {
        try (ConfigurableApplicationContext app = boot(SecondDispatcherServletConfig.class)) {
            assertChallengedUntilSignedIn(app, SecondDispatcherServletConfig.PREFIX + FEATURES_API);
            assertChallengedUntilSignedIn(app, SecondDispatcherServletConfig.PREFIX + DASHBOARD_ASSET);
        }
    }

    private static void assertChallengedUntilSignedIn(ConfigurableApplicationContext app, String path) {
        assertThat(apiOf(app).statusOf(path)).as("anonymous " + path).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(signedIn(app).statusOf(path)).as("signed in " + path).isEqualTo(HttpStatus.OK);
    }

    private ConfigurableApplicationContext boot(Class<?> hostConfiguration) {
        return new SpringApplicationBuilder(TestingApp.class, hostConfiguration)
                .profiles("auto-security")
                .run(
                        "--server.port=0",
                        "--peekaboot.security.credentials-file=" + credentialsDir.resolve("security.properties"));
    }

    private static PeekabootApi apiOf(ConfigurableApplicationContext app) {
        return new PeekabootApi(
                ((WebServerApplicationContext) app).getWebServer().getPort());
    }

    private static PeekabootApi signedIn(ConfigurableApplicationContext app) {
        DashboardCredentials credentials = app.getBean(DashboardCredentials.class);
        return apiOf(app).withBasicAuth(credentials.username(), credentials.plaintext());
    }
}
