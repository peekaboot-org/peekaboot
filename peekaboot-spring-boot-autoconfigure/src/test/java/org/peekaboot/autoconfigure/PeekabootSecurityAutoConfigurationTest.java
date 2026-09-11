package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.peekaboot.backend.security.DashboardAuthenticationFilter;
import org.peekaboot.backend.security.DashboardCredentials;
import org.peekaboot.backend.security.NeverAuthenticated;
import org.peekaboot.backend.security.RequestAuthentication;
import org.peekaboot.backend.security.SecurityContextRequestAuthentication;
import org.peekaboot.backend.security.SecurityPosture;
import org.peekaboot.backend.security.SecurityPostureListener;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.core.context.SecurityContextHolder;

class PeekabootSecurityAutoConfigurationTest {

    /**
     * Every case that resolves {@code DashboardCredentials} persists a password through
     * {@code CredentialsFile}, so every test points {@code peekaboot.storage.dir} at a
     * disposable directory rather than the real {@code user.home}.
     */
    private static WebApplicationContextRunner runner(Path storageDir) {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        PeekabootStorageAutoConfiguration.class, PeekabootSecurityAutoConfiguration.class))
                .withPropertyValues(
                        "peekaboot.enabled=true",
                        "peekaboot.security.enabled=true",
                        "peekaboot.storage.dir=" + storageDir);
    }

    @Test
    void registersTheGuardWhenSecurityIsEnabled(@TempDir Path storageDir) {
        runner(storageDir).run(context -> {
            assertThat(context).hasSingleBean(DashboardCredentials.class);
            assertThat(context).hasSingleBean(SecurityPostureListener.class);
            assertThat(context.getBean("dashboardAuthenticationFilter")).isNotNull();
        });
    }

    @Test
    void registersNoGuardWhilePeekabootIsOff(@TempDir Path storageDir) {
        runner(storageDir)
                .withPropertyValues("peekaboot.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(DashboardCredentials.class));
    }

    @Test
    void registersNoGuardWhileSecurityIsOff(@TempDir Path storageDir) {
        runner(storageDir)
                .withPropertyValues("peekaboot.security.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(DashboardCredentials.class));
    }

    /** The posture still reports, so switching the fallback off in a deployment is not silent. */
    @Test
    void stillRegistersThePostureListenerWhileSecurityIsOff(@TempDir Path storageDir) {
        runner(storageDir)
                .withPropertyValues("peekaboot.security.enabled=false")
                .run(context -> assertThat(context).hasSingleBean(SecurityPostureListener.class));
    }

    /**
     * A bare context runner never sets {@code peekaboot.security.enabled} itself - only the
     * launch-context detection does, in a real application. Without {@code matchIfMissing} on
     * the disarmed posture, this configuration would create neither posture bean here and
     * {@code securityPostureListener} would fail to resolve its dependency.
     */
    @Test
    void registersExactlyOnePostureBeanWithNoSecurityPropertyAtAll(@TempDir Path storageDir) {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        PeekabootStorageAutoConfiguration.class, PeekabootSecurityAutoConfiguration.class))
                .withPropertyValues("peekaboot.enabled=true", "peekaboot.storage.dir=" + storageDir)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SecurityPosture.class);
                    assertThat(context).doesNotHaveBean(DashboardCredentials.class);
                });
    }

    @Test
    void usesTheSecurityContextWhenSpringSecurityIsPresent(@TempDir Path storageDir) {
        runner(storageDir)
                .run(context -> assertThat(context.getBean(RequestAuthentication.class))
                        .isInstanceOf(SecurityContextRequestAuthentication.class));
    }

    @Test
    void fallsBackWhenSpringSecurityIsAbsent(@TempDir Path storageDir) {
        runner(storageDir)
                .withClassLoader(new FilteredClassLoader(SecurityContextHolder.class))
                .run(context -> assertThat(context.getBean(RequestAuthentication.class))
                        .isInstanceOf(NeverAuthenticated.class));
    }

    @Test
    void ordersTheGuardAfterSpringSecuritysOwnFilter(@TempDir Path storageDir) {
        runner(storageDir)
                .run(context -> assertThat(registration(context).getOrder()).isEqualTo(0));
    }

    @Test
    void followsAMovedSpringSecurityFilterOrder(@TempDir Path storageDir) {
        runner(storageDir)
                .withPropertyValues("spring.security.filter.order=500")
                .run(context -> assertThat(registration(context).getOrder()).isEqualTo(600));
    }

    /** Reads the detection source directly, so an explicit override does not hide what was detected. */
    @Test
    void reportsTheDeploymentWarningWhenDetectionMarkedTheLaunchADeployment(@TempDir Path storageDir) {
        runner(storageDir)
                .withPropertyValues("peekaboot.security.enabled=false")
                .withInitializer(context -> context.getEnvironment()
                        .getPropertySources()
                        .addLast(new MapPropertySource(
                                PeekabootPropertyKeys.DETECTION_PROPERTY_SOURCE_NAME,
                                Map.of(PeekabootPropertyKeys.SECURITY_ENABLED, true))))
                .run(context -> assertThat(
                                context.getBean(SecurityPosture.class).report())
                        .hasValueSatisfying(
                                report -> assertThat(report.level()).isEqualTo(SecurityPosture.Level.WARN)));
    }

    @Test
    void staysQuietWithoutADetectedDeployment(@TempDir Path storageDir) {
        runner(storageDir)
                .withPropertyValues("peekaboot.security.enabled=false")
                .run(context -> assertThat(
                                context.getBean(SecurityPosture.class).report())
                        .isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static FilterRegistrationBean<DashboardAuthenticationFilter> registration(ApplicationContext context) {
        return context.getBean("dashboardAuthenticationFilter", FilterRegistrationBean.class);
    }
}
