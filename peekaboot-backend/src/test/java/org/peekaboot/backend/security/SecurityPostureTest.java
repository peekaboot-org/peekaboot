package org.peekaboot.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.lifecycle.LifecycleBanner;

class SecurityPostureTest {

    private static final Path FILE = Path.of("/home/app/.peekaboot/com.acme.orders/security.properties");

    /** Without Spring Security nothing else can be authenticating the dashboard, so the exposure is certain. */
    @Test
    void armed_warnsAndPrintsTheGeneratedPasswordOnceWithoutSpringSecurity() {
        var report = SecurityPosture.armed(generated("hunter2"), FILE, false, false)
                .report()
                .orElseThrow();

        assertThat(report.level()).isEqualTo(SecurityPosture.Level.WARN);
        assertThat(report.text())
                .contains("orders-admin")
                .contains("hunter2")
                .contains(FILE.toString())
                .contains("https://www.peekaboot.org/docs/security/");
    }

    /** The application's chain may well cover the dashboard, so this is a note, not an alarm. */
    @Test
    void armed_onlyInformsWhenSpringSecurityIsPresent() {
        var report = SecurityPosture.armed(generated("hunter2"), FILE, true, false)
                .report()
                .orElseThrow();

        assertThat(report.level()).isEqualTo(SecurityPosture.Level.INFO);
    }

    @Test
    void armed_neverPrintsAPasswordItDidNotGenerate() {
        var loaded = new DashboardCredentials(
                "orders-admin",
                PasswordHash.of("hunter2"),
                null,
                Instant.parse("2026-09-11T08:15:30Z"),
                DashboardCredentials.Origin.LOADED);

        var report = SecurityPosture.armed(loaded, FILE, false, false).report().orElseThrow();

        assertThat(report.text()).doesNotContain("hunter2").contains("2026-09-11");
    }

    @Test
    void armed_saysNothingAboutAFileForAConfiguredPassword() {
        var configured = new DashboardCredentials(
                "orders-admin",
                PasswordHash.of("from-the-vault"),
                null,
                Instant.parse("2026-09-11T08:15:30Z"),
                DashboardCredentials.Origin.CONFIGURED);

        var report =
                SecurityPosture.armed(configured, FILE, false, false).report().orElseThrow();

        assertThat(report.text()).doesNotContain(FILE.toString()).contains("peekaboot.security.password");
    }

    @Test
    void armed_warnsThatAnUnpersistedPasswordWillChange() {
        var unpersisted = new DashboardCredentials(
                "orders-admin",
                PasswordHash.of("hunter2"),
                "hunter2",
                Instant.parse("2026-09-11T08:15:30Z"),
                DashboardCredentials.Origin.GENERATED_UNPERSISTED);

        var report =
                SecurityPosture.armed(unpersisted, FILE, false, false).report().orElseThrow();

        assertThat(report.text()).contains("change on the next restart");
    }

    @Test
    void armed_warnsAboutTheToolbarWhenItIsOn() {
        var report = SecurityPosture.armed(generated("hunter2"), FILE, false, true)
                .report()
                .orElseThrow();

        assertThat(report.text()).contains("dev toolbar");
    }

    @Test
    void disabledOnADeployment_isOneWarningLineWithNoBanner() {
        var report = SecurityPosture.disabledOnADeployment().report().orElseThrow();

        assertThat(report.level()).isEqualTo(SecurityPosture.Level.WARN);
        assertThat(report.text()).doesNotContain(LifecycleBanner.SEPARATOR).hasLineCount(1);
    }

    @Test
    void quiet_reportsNothing() {
        assertThat(SecurityPosture.quiet().report()).isEmpty();
    }

    private static DashboardCredentials generated(String password) {
        return new DashboardCredentials(
                "orders-admin",
                PasswordHash.of(password),
                password,
                Instant.parse("2026-09-11T08:15:30Z"),
                DashboardCredentials.Origin.GENERATED);
    }
}
