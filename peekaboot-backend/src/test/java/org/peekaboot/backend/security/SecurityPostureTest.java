package org.peekaboot.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.testsupport.Credentials;
import org.peekaboot.backend.text.BannerFrame;

class SecurityPostureTest {

    private static final Path FILE = Path.of("/home/app/.peekaboot/com.acme.orders/security.properties");

    private static final String DASHBOARD = "/peekaboot";

    /** Where {@code spring.mvc.servlet.path=/app} moves the dashboard, and the guard with it. */
    private static final String DASHBOARD_BEHIND_A_SERVLET_PATH = "/app/peekaboot";

    @Test
    void armedPrintsTheGeneratedPasswordOnce() {
        var report = SecurityPosture.armed(
                        DASHBOARD, Credentials.generated("hunter2"), FILE, false, false, ZoneOffset.UTC)
                .report()
                .orElseThrow();

        assertThat(report.text())
                .contains("orders-admin")
                .contains("hunter2")
                .contains(FILE.toString())
                .contains("https://www.peekaboot.org/docs/security/");
    }

    /** One headline whatever else is on the class path: the guard answers for the path on its own. */
    @Test
    void armedSaysTheGuardChallengesEveryRequest() {
        var report = SecurityPosture.armed(
                        DASHBOARD, Credentials.generated("hunter2"), FILE, false, false, ZoneOffset.UTC)
                .report()
                .orElseThrow();

        assertThat(report.text())
                .contains(" Peekaboot challenges every /peekaboot/** request with HTTP Basic.")
                .doesNotContain("stop-gap");
    }

    @Test
    void armedNamesThePathTheGuardActuallyCovers() {
        var report = SecurityPosture.armed(
                        DASHBOARD_BEHIND_A_SERVLET_PATH,
                        Credentials.generated("hunter2"),
                        FILE,
                        false,
                        false,
                        ZoneOffset.UTC)
                .report()
                .orElseThrow();

        assertThat(report.text()).contains(" Peekaboot challenges every /app/peekaboot/** request with HTTP Basic.");
    }

    @Test
    void armedNamesTheSwitchThatHandsThePathToYourOwnSecurity() {
        var report = SecurityPosture.armed(
                        DASHBOARD, Credentials.generated("hunter2"), FILE, false, false, ZoneOffset.UTC)
                .report()
                .orElseThrow();

        assertThat(report.text())
                .contains(" To use your own security instead, set peekaboot.security.enabled=false:"
                        + " https://www.peekaboot.org/docs/security/");
    }

    /** An armed guard is the intended setup, so it is a note, not an alarm. */
    @Test
    void armedOnlyInformsWhileTheToolbarIsOff() {
        var report = SecurityPosture.armed(
                        DASHBOARD, Credentials.generated("hunter2"), FILE, false, false, ZoneOffset.UTC)
                .report()
                .orElseThrow();

        assertThat(report.level()).isEqualTo(SecurityPosture.Level.INFO);
    }

    @Test
    void armedDatesALoadedPasswordInTheZoneItIsGiven() {
        var loaded = Credentials.loaded("hunter2");

        var inPagoPago = SecurityPosture.armed(DASHBOARD, loaded, FILE, false, false, ZoneId.of("Pacific/Pago_Pago"))
                .report()
                .orElseThrow();
        var inUtc = SecurityPosture.armed(DASHBOARD, loaded, FILE, false, false, ZoneOffset.UTC)
                .report()
                .orElseThrow();

        assertThat(inPagoPago.text()).contains("2026-09-10").doesNotContain("hunter2");
        assertThat(inUtc.text()).contains("2026-09-11").doesNotContain("hunter2");
    }

    @Test
    void armedSaysNothingAboutAFileForAConfiguredPassword() {
        var configured = Credentials.configured("from-the-vault");

        var report = SecurityPosture.armed(DASHBOARD, configured, FILE, false, false, ZoneOffset.UTC)
                .report()
                .orElseThrow();

        assertThat(report.text()).doesNotContain(FILE.toString()).contains("peekaboot.security.password");
    }

    @Test
    void armedWarnsThatAWriteFailureWillChangeThePassword() {
        var report = SecurityPosture.armed(
                        DASHBOARD, Credentials.unpersisted("hunter2"), FILE, false, false, ZoneOffset.UTC)
                .report()
                .orElseThrow();

        assertThat(report.text()).contains("change on the next restart").contains(FILE.toString());
    }

    @Test
    void armedSaysStorageIsOffWhenThereIsNoCredentialsFile() {
        var report = SecurityPosture.armed(
                        DASHBOARD, Credentials.unpersisted("hunter2"), null, false, false, ZoneOffset.UTC)
                .report()
                .orElseThrow();

        assertThat(report.text())
                .contains("hunter2")
                .contains("peekaboot.storage.enabled")
                .contains("peekaboot.security.password")
                .doesNotContain(FILE.toString());
    }

    @Test
    void armedWarnsAboutTheToolbarWhenItIsOn() {
        var report = SecurityPosture.armed(
                        DASHBOARD, Credentials.generated("hunter2"), FILE, true, false, ZoneOffset.UTC)
                .report()
                .orElseThrow();

        assertThat(report.text()).contains("dev toolbar");
    }

    /**
     * A WARN nested inside an INFO-level report is invisible to anyone running at WARN, which is
     * exactly the audience that needs to hear that the toolbar will now raise credential dialogs
     * on ordinary application pages.
     */
    @Test
    void armedWarnsWhenTheToolbarIsOn() {
        var report = SecurityPosture.armed(
                        DASHBOARD, Credentials.generated("hunter2"), FILE, true, false, ZoneOffset.UTC)
                .report()
                .orElseThrow();

        assertThat(report.level()).isEqualTo(SecurityPosture.Level.WARN);
    }

    @Test
    void disabledOnADeploymentIsOneWarningLineWithNoBanner() {
        var report =
                SecurityPosture.disabledOnADeployment(DASHBOARD, false).report().orElseThrow();

        assertThat(report.level()).isEqualTo(SecurityPosture.Level.WARN);
        assertThat(report.text()).doesNotContain(BannerFrame.SEPARATOR).hasLineCount(1);
    }

    @Test
    void disabledOnADeploymentSaysYourOwnSecurityMustAuthenticateTheDashboard() {
        var report =
                SecurityPosture.disabledOnADeployment(DASHBOARD, false).report().orElseThrow();

        assertThat(report.text())
                .contains("Peekaboot does not authenticate /peekaboot/**, so your application's own security must.")
                .doesNotContain("Nothing here authenticates");
    }

    @Test
    void disabledOnADeploymentNamesThePathYourOwnSecurityMustCover() {
        var report = SecurityPosture.disabledOnADeployment(DASHBOARD_BEHIND_A_SERVLET_PATH, false)
                .report()
                .orElseThrow();

        assertThat(report.text()).contains("Peekaboot does not authenticate /app/peekaboot/**");
    }

    /** Both warnings fire only on a launch detected as a deployment. */
    @Test
    void disabledOnADeploymentSaysItIsADeployment() {
        var report =
                SecurityPosture.disabledOnADeployment(DASHBOARD, true).report().orElseThrow();

        assertThat(report.text())
                .contains("peekaboot.security.enabled=false on a deployment.")
                .contains("peekaboot.error-page.enabled=true on a deployment:")
                .doesNotContain("outside local development");
    }

    @Test
    void remoteAccessOnALocalRunIsOneWarningLineNamingTheSwitchAndThePath() {
        var report = SecurityPosture.remoteAccessOnALocalRun(DASHBOARD_BEHIND_A_SERVLET_PATH)
                .report()
                .orElseThrow();

        assertThat(report.level()).isEqualTo(SecurityPosture.Level.WARN);
        assertThat(report.text())
                .isEqualTo("peekaboot.remote-access.enabled=true on a local run. Anyone who can reach this port"
                        + " reads /app/peekaboot/** without signing in and gets the dev toolbar and the error"
                        + " page. Set peekaboot.security.enabled=true to put Peekaboot's login in front. See"
                        + " https://www.peekaboot.org/docs/security/")
                .hasLineCount(1);
    }

    @Test
    void quietReportsNothing() {
        assertThat(SecurityPosture.quiet().report()).isEmpty();
    }

    /** The error page serves stack traces to anyone, outside the guard; an operator running at WARN has to hear it. */
    @Test
    void armedWarnsWhenTheErrorPageIsOnADeployment() {
        var report = SecurityPosture.armed(
                        DASHBOARD, Credentials.generated("hunter2"), FILE, false, true, ZoneOffset.UTC)
                .report()
                .orElseThrow();

        assertThat(report.level()).isEqualTo(SecurityPosture.Level.WARN);
        assertThat(report.text()).contains("peekaboot.error-page.enabled=true").contains("stack traces");
    }

    @Test
    void armedSaysNothingAboutTheErrorPageWhileItIsOff() {
        var report = SecurityPosture.armed(
                        DASHBOARD, Credentials.generated("hunter2"), FILE, false, false, ZoneOffset.UTC)
                .report()
                .orElseThrow();

        assertThat(report.text()).doesNotContain("peekaboot.error-page.enabled");
    }

    @Test
    void disabledOnADeploymentAddsTheErrorPageWarningAsASecondLine() {
        var report =
                SecurityPosture.disabledOnADeployment(DASHBOARD, true).report().orElseThrow();

        assertThat(report.level()).isEqualTo(SecurityPosture.Level.WARN);
        assertThat(report.text()).hasLineCount(2).contains("peekaboot.error-page.enabled=true");
    }
}
