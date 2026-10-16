package org.peekaboot.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.lifecycle.LifecycleBanner;

class SecurityPostureTest {

    private static final Path FILE = Path.of("/home/app/.peekaboot/com.acme.orders/security.properties");

    private static final Instant CREATED = Instant.parse("2026-09-11T08:15:30Z");

    private static final String DASHBOARD = "/peekaboot";

    /** Where {@code spring.mvc.servlet.path=/app} moves the dashboard, and the guard with it. */
    private static final String DASHBOARD_BEHIND_A_SERVLET_PATH = "/app/peekaboot";

    @Test
    void armed_printsTheGeneratedPasswordOnce() {
        var report = SecurityPosture.armed(DASHBOARD, generated("hunter2"), FILE, false, false)
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
    void armed_saysTheGuardChallengesEveryRequest() {
        var report = SecurityPosture.armed(DASHBOARD, generated("hunter2"), FILE, false, false)
                .report()
                .orElseThrow();

        assertThat(report.text())
                .contains(" Peekaboot challenges every /peekaboot/** request with HTTP Basic.")
                .doesNotContain("stop-gap");
    }

    @Test
    void armed_namesThePathTheGuardActuallyCovers() {
        var report = SecurityPosture.armed(DASHBOARD_BEHIND_A_SERVLET_PATH, generated("hunter2"), FILE, false, false)
                .report()
                .orElseThrow();

        assertThat(report.text()).contains(" Peekaboot challenges every /app/peekaboot/** request with HTTP Basic.");
    }

    @Test
    void armed_namesTheSwitchThatHandsThePathToYourOwnSecurity() {
        var report = SecurityPosture.armed(DASHBOARD, generated("hunter2"), FILE, false, false)
                .report()
                .orElseThrow();

        assertThat(report.text())
                .contains(" To use your own security instead, set peekaboot.security.enabled=false:"
                        + " https://www.peekaboot.org/docs/security/");
    }

    /** An armed guard is the intended setup, so it is a note, not an alarm. */
    @Test
    void armed_onlyInformsWhileTheToolbarIsOff() {
        var report = SecurityPosture.armed(DASHBOARD, generated("hunter2"), FILE, false, false)
                .report()
                .orElseThrow();

        assertThat(report.level()).isEqualTo(SecurityPosture.Level.INFO);
    }

    @Test
    void armed_neverPrintsAPasswordItDidNotGenerate() {
        var loaded = new DashboardCredentials(
                "orders-admin", PasswordHash.of("hunter2"), null, CREATED, DashboardCredentials.Origin.LOADED);

        var report = SecurityPosture.armed(DASHBOARD, loaded, FILE, false, false)
                .report()
                .orElseThrow();

        String expectedDate = DateTimeFormatter.ISO_LOCAL_DATE
                .withZone(ZoneId.systemDefault())
                .format(CREATED);
        assertThat(report.text()).doesNotContain("hunter2").contains(expectedDate);
    }

    @Test
    void armed_saysNothingAboutAFileForAConfiguredPassword() {
        var configured = new DashboardCredentials(
                "orders-admin",
                PasswordHash.of("from-the-vault"),
                null,
                CREATED,
                DashboardCredentials.Origin.CONFIGURED);

        var report = SecurityPosture.armed(DASHBOARD, configured, FILE, false, false)
                .report()
                .orElseThrow();

        assertThat(report.text()).doesNotContain(FILE.toString()).contains("peekaboot.security.password");
    }

    @Test
    void armed_warnsThatAWriteFailureWillChangeThePassword() {
        var report = SecurityPosture.armed(DASHBOARD, unpersisted("hunter2"), FILE, false, false)
                .report()
                .orElseThrow();

        assertThat(report.text()).contains("change on the next restart").contains(FILE.toString());
    }

    @Test
    void armed_saysStorageIsOffWhenThereIsNoCredentialsFile() {
        var report = SecurityPosture.armed(DASHBOARD, unpersisted("hunter2"), null, false, false)
                .report()
                .orElseThrow();

        assertThat(report.text())
                .contains("hunter2")
                .contains("peekaboot.storage.enabled")
                .contains("peekaboot.security.password")
                .doesNotContain(FILE.toString());
    }

    @Test
    void armed_warnsAboutTheToolbarWhenItIsOn() {
        var report = SecurityPosture.armed(DASHBOARD, generated("hunter2"), FILE, true, false)
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
    void armed_warnsWhenTheToolbarIsOn() {
        var report = SecurityPosture.armed(DASHBOARD, generated("hunter2"), FILE, true, false)
                .report()
                .orElseThrow();

        assertThat(report.level()).isEqualTo(SecurityPosture.Level.WARN);
    }

    @Test
    void disabledOnADeployment_isOneWarningLineWithNoBanner() {
        var report =
                SecurityPosture.disabledOnADeployment(DASHBOARD, false).report().orElseThrow();

        assertThat(report.level()).isEqualTo(SecurityPosture.Level.WARN);
        assertThat(report.text()).doesNotContain(LifecycleBanner.SEPARATOR).hasLineCount(1);
    }

    @Test
    void disabledOnADeployment_saysYourOwnSecurityMustAuthenticateTheDashboard() {
        var report =
                SecurityPosture.disabledOnADeployment(DASHBOARD, false).report().orElseThrow();

        assertThat(report.text())
                .contains("Peekaboot does not authenticate /peekaboot/**, so your application's own security must.")
                .doesNotContain("Nothing here authenticates");
    }

    @Test
    void disabledOnADeployment_namesThePathYourOwnSecurityMustCover() {
        var report = SecurityPosture.disabledOnADeployment(DASHBOARD_BEHIND_A_SERVLET_PATH, false)
                .report()
                .orElseThrow();

        assertThat(report.text()).contains("Peekaboot does not authenticate /app/peekaboot/**");
    }

    /** Both warnings fire only on a launch detected as a deployment. */
    @Test
    void disabledOnADeployment_saysItIsADeployment() {
        var report =
                SecurityPosture.disabledOnADeployment(DASHBOARD, true).report().orElseThrow();

        assertThat(report.text())
                .contains("peekaboot.security.enabled=false on a deployment.")
                .contains("peekaboot.error-page.enabled=true on a deployment:")
                .doesNotContain("outside local development");
    }

    @Test
    void quiet_reportsNothing() {
        assertThat(SecurityPosture.quiet().report()).isEmpty();
    }

    /** The error page serves stack traces to anyone, outside the guard; an operator running at WARN has to hear it. */
    @Test
    void armed_warnsWhenTheErrorPageIsOnADeployment() {
        var report = SecurityPosture.armed(DASHBOARD, generated("hunter2"), FILE, false, true)
                .report()
                .orElseThrow();

        assertThat(report.level()).isEqualTo(SecurityPosture.Level.WARN);
        assertThat(report.text()).contains("peekaboot.error-page.enabled=true").contains("stack traces");
    }

    @Test
    void armed_saysNothingAboutTheErrorPageWhileItIsOff() {
        var report = SecurityPosture.armed(DASHBOARD, generated("hunter2"), FILE, false, false)
                .report()
                .orElseThrow();

        assertThat(report.text()).doesNotContain("peekaboot.error-page.enabled");
    }

    @Test
    void disabledOnADeployment_addsTheErrorPageWarningAsASecondLine() {
        var report =
                SecurityPosture.disabledOnADeployment(DASHBOARD, true).report().orElseThrow();

        assertThat(report.level()).isEqualTo(SecurityPosture.Level.WARN);
        assertThat(report.text()).hasLineCount(2).contains("peekaboot.error-page.enabled=true");
    }

    private static DashboardCredentials generated(String password) {
        return new DashboardCredentials(
                "orders-admin", PasswordHash.of(password), password, CREATED, DashboardCredentials.Origin.GENERATED);
    }

    private static DashboardCredentials unpersisted(String password) {
        return new DashboardCredentials(
                "orders-admin",
                PasswordHash.of(password),
                password,
                CREATED,
                DashboardCredentials.Origin.GENERATED_UNPERSISTED);
    }
}
