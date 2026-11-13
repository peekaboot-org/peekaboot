package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import jakarta.servlet.Filter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.security.CredentialCache;
import org.peekaboot.backend.security.CredentialsFile;
import org.peekaboot.backend.security.DashboardAccess;
import org.peekaboot.backend.security.DashboardAuthenticationFilter;
import org.peekaboot.backend.security.DashboardCredentials;
import org.peekaboot.backend.security.PasswordHash;
import org.peekaboot.backend.security.SecurityPosture;
import org.peekaboot.backend.security.SecurityPostureListener;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.context.servlet.AnnotationConfigServletWebApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletPath;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

class PeekabootSecurityAutoConfigurationTest {

    /** Peekaboot's error page and the ErrorAttributes it renders with. */
    private static final AutoConfigurations ERROR_PAGE =
            AutoConfigurations.of(ErrorPageAutoConfiguration.class, ErrorMvcAutoConfiguration.class);

    @Test
    void registersTheGuardWhenSecurityIsEnabled(@TempDir Path storageDir) {
        runner(storageDir).run(context -> {
            assertThat(context).hasSingleBean(DashboardCredentials.class);
            assertThat(context).hasSingleBean(SecurityPostureListener.class);
            FilterRegistrationBean<DashboardAuthenticationFilter> registration = registration(context);
            assertThat(registration.getUrlPatterns()).containsExactly("/peekaboot/*");
            assertThat(registration.getFilter()).isInstanceOf(DashboardAuthenticationFilter.class);
        });
    }

    /** The artifact alone names the guard's user; the application name stands in without build-info. */
    @Test
    void derivesTheUsernameFromTheArtifactElseTheApplicationName(@TempDir Path storageDir) {
        Properties build = new Properties();
        build.setProperty("group", "com.acme");
        build.setProperty("artifact", "orders-service");
        runner(storageDir)
                .withBean(BuildProperties.class, () -> new BuildProperties(build))
                .withPropertyValues("spring.application.name=orders")
                .run(context -> assertThat(
                                context.getBean(DashboardCredentials.class).username())
                        .isEqualTo("orders-service-admin"));
        runner(storageDir.resolve("unbuilt"))
                .withPropertyValues("spring.application.name=orders")
                .run(context -> assertThat(
                                context.getBean(DashboardCredentials.class).username())
                        .isEqualTo("orders-admin"));
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
                .run(context -> {
                    assertThat(context).doesNotHaveBean(DashboardCredentials.class);
                    assertThat(context).doesNotHaveBean("peekabootDashboardAuthenticationFilter");
                });
    }

    /** The posture still reports, so switching the guard off in a deployment is not silent. */
    @Test
    void stillRegistersThePostureListenerWhileSecurityIsOff(@TempDir Path storageDir) {
        runner(storageDir)
                .withPropertyValues("peekaboot.security.enabled=false")
                .run(context -> assertThat(context).hasSingleBean(SecurityPostureListener.class));
    }

    /**
     * A bare context runner never sets {@code peekaboot.security.enabled} itself - only the
     * launch-context detection does, in a real application. The posture bean is unconditional
     * and keyed off what actually resolved rather than off this property, so its absence still
     * leaves exactly one posture bean and no guard.
     */
    @Test
    void registersExactlyOnePostureBeanWithNoSecurityPropertyAtAll(@TempDir Path storageDir) {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DispatcherServletAutoConfiguration.class,
                        PeekabootPathsAutoConfiguration.class,
                        PeekabootStorageAutoConfiguration.class,
                        PeekabootSecurityAutoConfiguration.class))
                .withPropertyValues("peekaboot.enabled=true", "peekaboot.storage.dir=" + storageDir)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SecurityPosture.class);
                    assertThat(context).doesNotHaveBean(DashboardCredentials.class);
                });
    }

    /**
     * {@code @ConditionalOnBooleanProperty} matches neither {@code true} nor {@code false} for a
     * value like this, so a typo here must not be able to leave zero posture beans and break
     * {@code peekabootSecurityPostureListener}'s dependency resolution.
     */
    @Test
    void contextStartsWithANonBooleanSecurityEnabledValue(@TempDir Path storageDir) {
        runner(storageDir).withPropertyValues("peekaboot.security.enabled=yes").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SecurityPosture.class);
            assertThat(context).doesNotHaveBean(DashboardCredentials.class);
        });
    }

    @Test
    void credentialsFileHasNoPathWhenStorageIsOff(@TempDir Path storageDir) {
        runner(storageDir)
                .withPropertyValues("peekaboot.storage.enabled=false")
                .run(context -> assertThat(
                                context.getBean(CredentialsFile.class).path())
                        .isEmpty());
    }

    @Test
    void credentialsFileSitsUnderTheStorageDirectoryWhenStorageIsOn(@TempDir Path storageDir) {
        runner(storageDir)
                .run(context -> assertThat(
                                context.getBean(CredentialsFile.class).path())
                        .contains(storageDir.resolve("security.properties")));
    }

    @Test
    void anExplicitCredentialsFileWinsOverStorageBeingOff(@TempDir Path storageDir, @TempDir Path credentialsDir) {
        Path explicit = credentialsDir.resolve("security.properties");

        runner(storageDir)
                .withPropertyValues(
                        "peekaboot.storage.enabled=false", "peekaboot.security.credentials-file=" + explicit)
                .run(context -> assertThat(
                                context.getBean(CredentialsFile.class).path())
                        .contains(explicit));
    }

    @Test
    void securityPostureIsArmedWhenTheGuardResolves(@TempDir Path storageDir) {
        runner(storageDir)
                .run(context -> assertThat(
                                context.getBean(SecurityPosture.class).report())
                        .isPresent());
    }

    /** The toolbar's requests hit the guard too, so an armed posture with the toolbar on warns about the dialog. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void theArmedPostureFollowsTheToolbarSwitch(boolean devToolbar, @TempDir Path storageDir) {
        runner(storageDir)
                .withPropertyValues("peekaboot.dev-toolbar.enabled=" + devToolbar)
                .run(context -> assertThat(
                                context.getBean(SecurityPosture.class).report())
                        .hasValueSatisfying(report -> {
                            assertThat(report.text().contains("The dev toolbar is on"))
                                    .isEqualTo(devToolbar);
                            assertThat(report.level())
                                    .isEqualTo(devToolbar ? SecurityPosture.Level.WARN : SecurityPosture.Level.INFO);
                        }));
    }

    /** The controllers sit under the DispatcherServlet's path, so the guard has to sit there too. */
    @ParameterizedTest
    @ValueSource(strings = {"/app", "/app/"})
    void guardsTheDashboardUnderTheDispatcherServletsPath(String servletPath, @TempDir Path storageDir) {
        runner(storageDir)
                .withPropertyValues("spring.mvc.servlet.path=" + servletPath)
                .run(context ->
                        assertThat(registration(context).getUrlPatterns()).containsExactly("/app/peekaboot/*"));
    }

    /** The startup report names the path the guard covers, not the root mapping. */
    @Test
    void reportsTheGuardedPathUnderTheDispatcherServletsPath(@TempDir Path storageDir) {
        runner(storageDir)
                .withPropertyValues("spring.mvc.servlet.path=/app")
                .run(context -> assertThat(
                                context.getBean(SecurityPosture.class).report())
                        .hasValueSatisfying(
                                report -> assertThat(report.text()).contains(" /app/peekaboot/** request")));
    }

    @Test
    void guardsTheRootMappingUnderTheDefaultServletPath(@TempDir Path storageDir) {
        runner(storageDir)
                .run(context ->
                        assertThat(registration(context).getUrlPatterns()).containsExactly("/peekaboot/*"));
    }

    /** Spring MVC without a DispatcherServletPath bean: the root mapping is an assumption, and startup says so. */
    @Test
    void warnsThatItAssumesTheRootMappingWithoutADispatcherServletPath(@TempDir Path storageDir) {
        try (LogCapture logs = LogCapture.attach(PeekabootPathsAutoConfiguration.class)) {
            new WebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(PeekabootPathsAutoConfiguration.class))
                    .withPropertyValues("peekaboot.enabled=true", "peekaboot.storage.dir=" + storageDir)
                    .run(context -> assertThat(
                                    context.getBean(PeekabootPaths.class).dashboardPath())
                            .isEqualTo("/peekaboot"));

            assertThat(logs.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .contains("DispatcherServletPath")
                        .contains("/peekaboot/*");
            });
        }
    }

    @Test
    void saysNothingWhereTheDispatcherServletPathIsKnown(@TempDir Path storageDir) {
        try (LogCapture logs = LogCapture.attach(PeekabootPathsAutoConfiguration.class)) {
            runner(storageDir).run(context -> assertThat(context).hasNotFailed());

            assertThat(logs.appender().list).isEmpty();
        }
    }

    /** No Spring MVC, nothing moves the dashboard: the guard still registers, at the root mapping. */
    @Test
    void keepsTheRootMappingWithoutSpringMvc(@TempDir Path storageDir) {
        runner(storageDir)
                .withClassLoader(new FilteredClassLoader(DispatcherServletPath.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(registration(context).getUrlPatterns()).containsExactly("/peekaboot/*");
                });
    }

    /** A moved Spring Security filter does not move the guard: it answers for the path on its own. */
    @Test
    void keepsTheGuardAtOrderZeroWhereverSpringSecuritysFilterSits(@TempDir Path storageDir) {
        runner(storageDir)
                .withPropertyValues("spring.security.filter.order=500")
                .run(context -> assertThat(registration(context).getOrder()).isEqualTo(0));
    }

    /**
     * Detected-only: an explicit override of {@code peekaboot.security.enabled} does not hide
     * what the launch context detected - see {@link DetectedLaunch}.
     */
    @Test
    void reportsTheDeploymentWarningWhenDetectionMarkedTheLaunchADeployment(@TempDir Path storageDir) {
        runner(storageDir)
                .withInitializer(DetectedLaunches.as(LocalDevDetector.LaunchKind.DEPLOYMENT))
                .withPropertyValues("peekaboot.security.enabled=false")
                .run(context -> assertThat(
                                context.getBean(SecurityPosture.class).report())
                        .hasValueSatisfying(
                                report -> assertThat(report.level()).isEqualTo(SecurityPosture.Level.WARN)));
    }

    /** The posture asks the detected launch itself, so no property hides a deployment from it. */
    @Test
    void aPropertyCannotSilenceTheDeploymentWarning(@TempDir Path storageDir) {
        runner(storageDir)
                .withInitializer(DetectedLaunches.as(LocalDevDetector.LaunchKind.DEPLOYMENT))
                .withPropertyValues("peekaboot.security.enabled=false", "peekaboot.security.deployment-detected=false")
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

    @Test
    void warnsAboutTheErrorPageOnADeploymentWithTheGuardArmed(@TempDir Path storageDir) {
        runner(storageDir)
                .withConfiguration(ERROR_PAGE)
                .withInitializer(DetectedLaunches.as(LocalDevDetector.LaunchKind.DEPLOYMENT))
                .withPropertyValues("peekaboot.error-page.enabled=true")
                .run(context -> assertThat(
                                context.getBean(SecurityPosture.class).report())
                        .hasValueSatisfying(report -> {
                            assertThat(report.level()).isEqualTo(SecurityPosture.Level.WARN);
                            assertThat(report.text()).contains("peekaboot.error-page.enabled=true");
                        }));
    }

    @Test
    void warnsAboutTheErrorPageOnADeploymentWithTheGuardOff(@TempDir Path storageDir) {
        runner(storageDir)
                .withConfiguration(ERROR_PAGE)
                .withInitializer(DetectedLaunches.as(LocalDevDetector.LaunchKind.DEPLOYMENT))
                .withPropertyValues("peekaboot.security.enabled=false", "peekaboot.error-page.enabled=true")
                .run(context -> assertThat(
                                context.getBean(SecurityPosture.class).report())
                        .hasValueSatisfying(
                                report -> assertThat(report.text()).contains("peekaboot.error-page.enabled=true")));
    }

    /** The application's own templates/error.html backs Peekaboot's page off, so no page escapes the guard. */
    @Test
    void saysNothingAboutAnErrorPageThatBackedOffForTheApplicationsTemplate(@TempDir Path storageDir) {
        runner(storageDir)
                .withConfiguration(ERROR_PAGE)
                .withInitializer(DetectedLaunches.as(LocalDevDetector.LaunchKind.DEPLOYMENT))
                .withPropertyValues("peekaboot.error-page.enabled=true", "test.error-template=true")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("error");
                    assertThat(context.getBean(SecurityPosture.class).report())
                            .hasValueSatisfying(
                                    report -> assertThat(report.text()).doesNotContain("peekaboot.error-page.enabled"));
                });
    }

    /** The override registers the page whatever templates the application has, so the warning stands. */
    @Test
    void warnsAboutAnOverridingErrorPageDespiteTheApplicationsTemplate(@TempDir Path storageDir) {
        runner(storageDir)
                .withConfiguration(ERROR_PAGE)
                .withInitializer(DetectedLaunches.as(LocalDevDetector.LaunchKind.DEPLOYMENT))
                .withPropertyValues(
                        "peekaboot.error-page.enabled=true",
                        "peekaboot.error-page.override=true",
                        "test.error-template=true")
                .run(context -> assertThat(
                                context.getBean(SecurityPosture.class).report())
                        .hasValueSatisfying(
                                report -> assertThat(report.text()).contains("peekaboot.error-page.enabled=true")));
    }

    /** A local run is where the page belongs. */
    @Test
    void saysNothingAboutTheErrorPageOnALocalRun(@TempDir Path storageDir) {
        runner(storageDir)
                .withConfiguration(ERROR_PAGE)
                .withPropertyValues("peekaboot.error-page.enabled=true")
                .run(context -> assertThat(
                                context.getBean(SecurityPosture.class).report())
                        .hasValueSatisfying(
                                report -> assertThat(report.text()).doesNotContain("peekaboot.error-page.enabled")));
    }

    /**
     * With no guard of its own to find, Spring retries the lookup ignoring generics, and every
     * other {@code FilterRegistrationBean} in the application is a candidate on that pass - two of
     * them used to fail the context. Zalando's Logbook contributes exactly this pair.
     */
    @Test
    void startsWithoutAGuardAlongsideForeignFilterRegistrations(@TempDir Path storageDir) {
        runner(storageDir)
                .withPropertyValues("peekaboot.security.enabled=false")
                .withUserConfiguration(ForeignFilterRegistrations.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SecurityPosture.class).report()).isEmpty();
                });
    }

    /** A single foreign registration is what that same pass would arm the posture behind. */
    @Test
    void staysQuietWithoutAGuardAlongsideOneForeignFilterRegistration(@TempDir Path storageDir) {
        runner(storageDir)
                .withPropertyValues("peekaboot.security.enabled=false")
                .withUserConfiguration(OneForeignFilterRegistration.class)
                .run(context -> assertThat(
                                context.getBean(SecurityPosture.class).report())
                        .isEmpty());
    }

    /** Foreign registrations must not back the guard off through {@code @ConditionalOnMissingBean} either. */
    @Test
    void stillRegistersTheGuardAlongsideForeignFilterRegistrations(@TempDir Path storageDir) {
        runner(storageDir)
                .withUserConfiguration(ForeignFilterRegistrations.class)
                .run(context -> {
                    assertThat(context).hasBean("peekabootDashboardAuthenticationFilter");
                    assertThat(context.getBean(SecurityPosture.class).report()).isPresent();
                });
    }

    /** The handler-level check and the filter give one answer, the registered guard's. */
    @Test
    void theHandlerCheckAsksTheRegisteredGuardsCredentials(@TempDir Path storageDir) {
        runner(storageDir).run(context -> {
            DashboardCredentials credentials = context.getBean(DashboardCredentials.class);
            DashboardAccess access = context.getBean(DashboardAccess.class);
            MockHttpServletResponse challenged = new MockHttpServletResponse();

            assertThat(admits(access, credentials.username(), credentials.plaintext()))
                    .isTrue();
            assertThat(access.admit(new MockHttpServletRequest(), challenged)).isFalse();
            assertThat(challenged.getStatus()).isEqualTo(401);
        });
    }

    /**
     * An application that replaced the guard keeps one set of credentials for the dashboard. A
     * handler check that asked Peekaboot's generated ones would refuse every request the
     * application's filter let through.
     */
    @Test
    void theHandlerCheckFollowsAGuardTheApplicationReplaced(@TempDir Path storageDir) {
        runner(storageDir).withUserConfiguration(ReplacedGuard.class).run(context -> {
            DashboardCredentials generated = context.getBean(DashboardCredentials.class);
            DashboardAccess access = context.getBean(DashboardAccess.class);

            assertThat(admits(access, ReplacedGuard.USERNAME, ReplacedGuard.PASSWORD))
                    .isTrue();
            assertThat(admits(access, generated.username(), generated.plaintext()))
                    .isFalse();
        });
    }

    @Test
    void withoutAGuardTheHandlerCheckAdmitsEveryRequest(@TempDir Path storageDir) {
        runner(storageDir)
                .withPropertyValues("peekaboot.security.enabled=false")
                .run(context -> {
                    MockHttpServletResponse response = new MockHttpServletResponse();

                    assertThat(context.getBean(DashboardAccess.class).admit(new MockHttpServletRequest(), response))
                            .isTrue();
                    assertThat(response.getStatus()).isEqualTo(200);
                });
    }

    /**
     * A guard that cannot say what it asks must not leave the handlers open behind it. The context
     * logs the failed refresh as a WARN, which this test asserts and keeps off the console.
     */
    @Test
    void refusesToStartBehindAGuardRegistrationThatHoldsNoFilter(@TempDir Path storageDir) {
        try (LogCapture refreshWarnings = LogCapture.attach(AnnotationConfigServletWebApplicationContext.class)) {
            runner(storageDir)
                    .withUserConfiguration(GuardWithoutItsFilter.class)
                    .run(context -> assertThat(context)
                            .getFailure()
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("emptyGuard")
                            .hasMessageContaining("DashboardAuthenticationFilter"));

            assertThat(refreshWarnings.appender().list).singleElement().satisfies(warning -> {
                assertThat(warning.getLevel()).isEqualTo(Level.WARN);
                assertThat(warning.getFormattedMessage())
                        .contains("cancelling refresh attempt")
                        .contains("emptyGuard");
            });
        }
    }

    @Test
    void registersTheHandlerCheckWithSpringMvc(@TempDir Path storageDir) {
        runner(storageDir).run(context -> assertThat(context).hasBean("peekabootDashboardAccessConfigurer"));
    }

    /** A servlet application without Spring MVC has no handlers to check, and the filter still guards it. */
    @Test
    void registersNoHandlerCheckWithoutSpringMvc(@TempDir Path storageDir) {
        runner(storageDir)
                .withClassLoader(new FilteredClassLoader(WebMvcConfigurer.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("peekabootDashboardAccessConfigurer");
                    assertThat(context).hasSingleBean(DashboardAccess.class);
                });
    }

    /** No login stands in front of a local run, so only the machine it runs on is answered. */
    @Test
    void aLocalRunWithoutTheGuardAnswersLoopbackOnly(@TempDir Path storageDir) {
        runner(storageDir)
                .withInitializer(DetectedLaunches.as(LocalDevDetector.LaunchKind.LOCAL_DEV))
                .withPropertyValues("peekaboot.security.enabled=false")
                .run(context -> {
                    DashboardAccess access = context.getBean(DashboardAccess.class);
                    MockHttpServletResponse refused = new MockHttpServletResponse();

                    assertThat(access.admit(fromAnotherMachine(), refused)).isFalse();
                    assertThat(refused.getStatus()).isEqualTo(403);
                    assertThat(access.admit(fromThisMachine(), new MockHttpServletResponse()))
                            .isTrue();
                });
    }

    @Test
    void remoteAccessOpensALocalRun(@TempDir Path storageDir) {
        runner(storageDir)
                .withInitializer(DetectedLaunches.as(LocalDevDetector.LaunchKind.LOCAL_DEV))
                .withPropertyValues("peekaboot.security.enabled=false", "peekaboot.remote-access.enabled=true")
                .run(context -> assertThat(admitsFromAnotherMachine(context)).isTrue());
    }

    /** The login is the answer to a remote client there, so the loopback rule never refuses one it would admit. */
    @Test
    void anArmedGuardOnALocalRunDecidesAlone(@TempDir Path storageDir) {
        runner(storageDir)
                .withInitializer(DetectedLaunches.as(LocalDevDetector.LaunchKind.LOCAL_DEV))
                .run(context -> {
                    DashboardCredentials credentials = context.getBean(DashboardCredentials.class);
                    DashboardAccess access = context.getBean(DashboardAccess.class);
                    MockHttpServletResponse challenged = new MockHttpServletResponse();
                    MockHttpServletRequest signedIn = fromAnotherMachine();
                    signedIn.addHeader("Authorization", basic(credentials.username(), credentials.plaintext()));

                    assertThat(access.admit(fromAnotherMachine(), challenged)).isFalse();
                    assertThat(challenged.getStatus()).isEqualTo(401);
                    assertThat(access.admit(signedIn, new MockHttpServletResponse()))
                            .isTrue();
                });
    }

    /** The operator handed the path to the application's own security, which may sit behind any proxy. */
    @Test
    void aDeploymentWithTheGuardOffIsNotLoopbackOnly(@TempDir Path storageDir) {
        runner(storageDir)
                .withInitializer(DetectedLaunches.as(LocalDevDetector.LaunchKind.DEPLOYMENT))
                .withPropertyValues("peekaboot.security.enabled=false")
                .run(context -> assertThat(admitsFromAnotherMachine(context)).isTrue());
    }

    /** A test drives the application with whatever client and Host it likes. */
    @Test
    void aTestLaunchIsNotLoopbackOnly(@TempDir Path storageDir) {
        runner(storageDir)
                .withPropertyValues("peekaboot.security.enabled=false")
                .run(context -> assertThat(admitsFromAnotherMachine(context)).isTrue());
    }

    @Test
    void warnsThatRemoteAccessLeavesALocalRunOpen(@TempDir Path storageDir) {
        runner(storageDir)
                .withInitializer(DetectedLaunches.as(LocalDevDetector.LaunchKind.LOCAL_DEV))
                .withPropertyValues("peekaboot.security.enabled=false", "peekaboot.remote-access.enabled=true")
                .run(context -> assertThat(
                                context.getBean(SecurityPosture.class).report())
                        .hasValueSatisfying(report -> {
                            assertThat(report.level()).isEqualTo(SecurityPosture.Level.WARN);
                            assertThat(report.text())
                                    .contains("peekaboot.remote-access.enabled=true on a local run")
                                    .contains(" /peekaboot/** ");
                        }));
    }

    @Test
    void staysQuietOnALoopbackOnlyLocalRun(@TempDir Path storageDir) {
        runner(storageDir)
                .withInitializer(DetectedLaunches.as(LocalDevDetector.LaunchKind.LOCAL_DEV))
                .withPropertyValues("peekaboot.security.enabled=false")
                .run(context -> assertThat(
                                context.getBean(SecurityPosture.class).report())
                        .isEmpty());
    }

    /** The switch opens nothing there, so there is nothing to warn about. */
    @Test
    void saysNothingAboutRemoteAccessBehindAnArmedGuard(@TempDir Path storageDir) {
        runner(storageDir)
                .withInitializer(DetectedLaunches.as(LocalDevDetector.LaunchKind.LOCAL_DEV))
                .withPropertyValues("peekaboot.remote-access.enabled=true")
                .run(context -> assertThat(
                                context.getBean(SecurityPosture.class).report())
                        .hasValueSatisfying(report -> assertThat(report.text())
                                .contains("Peekaboot challenges every /peekaboot/** request")
                                .doesNotContain("peekaboot.remote-access.enabled")));
    }

    /** The application's own guard asks for credentials, so nobody reads the dashboard without signing in. */
    @Test
    void saysNothingAboutRemoteAccessBehindAGuardTheApplicationReplaced(@TempDir Path storageDir) {
        runner(storageDir)
                .withInitializer(DetectedLaunches.as(LocalDevDetector.LaunchKind.LOCAL_DEV))
                .withUserConfiguration(ReplacedGuard.class)
                .withPropertyValues("peekaboot.security.enabled=false", "peekaboot.remote-access.enabled=true")
                .run(context -> assertThat(
                                context.getBean(SecurityPosture.class).report())
                        .isEmpty());
    }

    @Test
    void saysNothingAboutRemoteAccessOnATestLaunch(@TempDir Path storageDir) {
        runner(storageDir)
                .withPropertyValues("peekaboot.security.enabled=false", "peekaboot.remote-access.enabled=true")
                .run(context -> assertThat(
                                context.getBean(SecurityPosture.class).report())
                        .isEmpty());
    }

    /** The deployment's own warning stands: the switch means nothing there. */
    @Test
    void saysNothingAboutRemoteAccessOnADeployment(@TempDir Path storageDir) {
        runner(storageDir)
                .withInitializer(DetectedLaunches.as(LocalDevDetector.LaunchKind.DEPLOYMENT))
                .withPropertyValues("peekaboot.security.enabled=false", "peekaboot.remote-access.enabled=true")
                .run(context -> assertThat(
                                context.getBean(SecurityPosture.class).report())
                        .hasValueSatisfying(report -> assertThat(report.text())
                                .contains("peekaboot.security.enabled=false on a deployment")
                                .doesNotContain("peekaboot.remote-access.enabled")));
    }

    private static boolean admitsFromAnotherMachine(ApplicationContext context) throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean admitted = context.getBean(DashboardAccess.class).admit(fromAnotherMachine(), response);
        assertThat(response.getStatus()).isEqualTo(admitted ? 200 : 403);
        return admitted;
    }

    private static MockHttpServletRequest fromAnotherMachine() {
        MockHttpServletRequest request = fromThisMachine();
        request.setRemoteAddr("10.0.0.5");
        return request;
    }

    private static MockHttpServletRequest fromThisMachine() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/peekaboot/api/features");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Host", "localhost");
        return request;
    }

    private static boolean admits(DashboardAccess access, String username, String password) throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", basic(username, password));
        return access.admit(request, new MockHttpServletResponse());
    }

    private static String basic(String username, String password) {
        return "Basic "
                + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static FilterRegistrationBean<DashboardAuthenticationFilter> registration(ApplicationContext context) {
        return context.getBean("peekabootDashboardAuthenticationFilter", FilterRegistrationBean.class);
    }

    /** Declared as {@code FilterRegistrationBean<?>}, the way Logbook declares its two. */
    @Configuration(proxyBeanMethods = false)
    static class ForeignFilterRegistrations {

        @Bean
        FilterRegistrationBean<?> logbookFilter() {
            return passThrough();
        }

        @Bean
        FilterRegistrationBean<?> secureLogbookFilter() {
            return passThrough();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OneForeignFilterRegistration {

        @Bean
        FilterRegistrationBean<?> logbookFilter() {
            return passThrough();
        }
    }

    /** The application's own guard, over credentials Peekaboot never generated. */
    @Configuration(proxyBeanMethods = false)
    static class ReplacedGuard {

        static final String USERNAME = "its-admin";
        static final String PASSWORD = "its-own";

        @Bean
        FilterRegistrationBean<DashboardAuthenticationFilter> applicationsOwnGuard() {
            DashboardCredentials credentials = new DashboardCredentials(
                    USERNAME,
                    PasswordHash.of(PASSWORD),
                    null,
                    Instant.parse("2026-09-11T08:15:30Z"),
                    DashboardCredentials.Origin.CONFIGURED);
            return new FilterRegistrationBean<>(new DashboardAuthenticationFilter(credentials, new CredentialCache()));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class GuardWithoutItsFilter {

        @Bean
        FilterRegistrationBean<DashboardAuthenticationFilter> emptyGuard() {
            return new FilterRegistrationBean<>();
        }
    }

    private static FilterRegistrationBean<?> passThrough() {
        Filter filter = (request, response, chain) -> chain.doFilter(request, response);
        return new FilterRegistrationBean<>(filter);
    }

    /**
     * Every case that resolves {@code DashboardCredentials} persists a password through
     * {@code CredentialsFile}, so every test turns storage on and points
     * {@code peekaboot.storage.dir} at a disposable directory rather than the real
     * {@code user.home}.
     */
    private static WebApplicationContextRunner runner(Path storageDir) {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DispatcherServletAutoConfiguration.class,
                        PeekabootPathsAutoConfiguration.class,
                        PeekabootStorageAutoConfiguration.class,
                        PeekabootSecurityAutoConfiguration.class))
                .withPropertyValues(
                        "peekaboot.enabled=true",
                        "peekaboot.security.enabled=true",
                        "peekaboot.storage.enabled=true",
                        "peekaboot.storage.dir=" + storageDir);
    }
}
