package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

class PeekabootDefaultsEnvironmentPostProcessorTest {

    /**
     * Real detection inspects the launch context (thread, classloader, stack),
     * which inside a JUnit run always reads "not local dev"; the override pins
     * the detection result so both branches are testable.
     */
    private PeekabootDefaultsEnvironmentPostProcessor postProcessor(boolean localDevelopment) {
        return new PeekabootDefaultsEnvironmentPostProcessor(java.util.function.Supplier::get) {
            @Override
            boolean localDevelopment() {
                return localDevelopment;
            }
        };
    }

    /** Pinned rather than deduced from the test classpath: the defaults depend on the web type. */
    private static SpringApplication servletApplication() {
        return application(WebApplicationType.SERVLET);
    }

    private static SpringApplication application(WebApplicationType webApplicationType) {
        SpringApplication application = new SpringApplication();
        application.setWebApplicationType(webApplicationType);
        return application;
    }

    @Test
    void enablesPeekabootByDefaultInLocalDevelopment() {
        MockEnvironment environment = new MockEnvironment();

        postProcessor(true).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("peekaboot.enabled", Boolean.class)).isTrue();
    }

    @Test
    void disablesPeekabootByDefaultOutsideLocalDevelopment() {
        MockEnvironment environment = new MockEnvironment();

        postProcessor(false).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("peekaboot.enabled", Boolean.class)).isFalse();
        assertThat(environment.getPropertySources().contains("peekabootDefaults"))
                .isFalse();
        assertThat(environment.getProperty("management.info.java.enabled")).isNull();
    }

    @Test
    void explicitEnabledTrueWinsOverDetection() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("peekaboot.enabled", "true");

        postProcessor(false).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("peekaboot.enabled", Boolean.class)).isTrue();
        assertThat(environment.getPropertySources().contains("peekabootDefaults"))
                .isTrue();
    }

    @Test
    void explicitEnabledFalseWinsOverDetection() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("peekaboot.enabled", "false");

        postProcessor(true).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("peekaboot.enabled", Boolean.class)).isFalse();
        assertThat(environment.getPropertySources().contains("peekabootDefaults"))
                .isFalse();
        assertThat(environment.getProperty("management.info.java.enabled")).isNull();
    }

    @Test
    void disablesOtlpMetricsExportWhenPeekabootEnabled() {
        MockEnvironment environment = new MockEnvironment();

        postProcessor(true).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("management.otlp.metrics.export.enabled"))
                .isEqualTo("false");
    }

    @Test
    void disablesOtlpMetricsExportEvenWhenPeekabootDisabled() {
        MockEnvironment environment = new MockEnvironment();

        postProcessor(false).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("management.otlp.metrics.export.enabled"))
                .isEqualTo("false");
    }

    @Test
    void appPropertiesCanReenableOtlpMetricsExport() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("management.otlp.metrics.export.enabled", "true");

        postProcessor(false).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("management.otlp.metrics.export.enabled"))
                .isEqualTo("true");
    }

    @Test
    void loadsDefaultProperties() {
        ConfigurableEnvironment environment = new MockEnvironment();

        postProcessor(true).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("management.info.java.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("management.tracing.sampling.probability"))
                .isEqualTo("1.0");
    }

    /**
     * The dashboard reads health through the HealthEndpoint bean, which always carries the
     * components, so Peekaboot has no reason to widen the application's own public
     * /actuator/health - Spring's default (aggregate status only) stays in force.
     */
    @Test
    void leavesTheHostsHealthShowDetailsAlone() {
        MockEnvironment environment = new MockEnvironment();

        postProcessor(true).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("management.endpoint.health.show-details"))
                .isNull();
    }

    /**
     * The dashboard, its filters and the trace store are servlet-only, so a WebFlux or
     * non-web application launched locally has nothing that would read the observability
     * defaults - Hibernate statistics, full sampling and the observation annotations would
     * only cost. Detection (the activation switches, storage, value visibility) and the
     * no-push default still apply: they gate the lifecycle and storage beans, which are not
     * servlet-bound.
     */
    @Test
    void skipsTheObservabilityDefaultsForAReactiveApplication() {
        MockEnvironment environment = new MockEnvironment();

        postProcessor(true).postProcessEnvironment(environment, application(WebApplicationType.REACTIVE));

        assertThat(environment.getProperty("peekaboot.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("peekaboot.storage.enabled", Boolean.class))
                .isTrue();
        assertThat(environment.getProperty("management.otlp.metrics.export.enabled"))
                .isEqualTo("false");
        assertThat(environment.getPropertySources().contains("peekabootDefaults"))
                .isFalse();
        assertThat(environment.getProperty("management.tracing.sampling.probability"))
                .isNull();
        assertThat(environment.getPropertySources().contains("peekabootDevToolbarDefaults"))
                .isFalse();
        assertThat(environment.getProperty("management.opentelemetry.tracing.export.schedule-delay"))
                .isNull();
    }

    @Test
    void skipsTheObservabilityDefaultsForANonWebApplication() {
        MockEnvironment environment = new MockEnvironment();

        postProcessor(true).postProcessEnvironment(environment, application(WebApplicationType.NONE));

        assertThat(environment.getProperty("peekaboot.enabled", Boolean.class)).isTrue();
        assertThat(environment.getPropertySources().contains("peekabootDefaults"))
                .isFalse();
        assertThat(environment.getPropertySources().contains("peekabootDevToolbarDefaults"))
                .isFalse();
    }

    @Test
    void showsActuatorValuesSoTheEnvironmentAndConfigTabsAreReadable() {
        ConfigurableEnvironment environment = new MockEnvironment();

        postProcessor(true).postProcessEnvironment(environment, servletApplication());

        // Value visibility follows the launch context, not peekaboot.enabled: local
        // development is exactly when the Environment and Config tabs should show real
        // values rather than "******" for every entry.
        assertThat(environment.getProperty("management.endpoint.env.show-values"))
                .isEqualTo("always");
        assertThat(environment.getProperty("management.endpoint.configprops.show-values"))
                .isEqualTo("always");
    }

    /**
     * The placement decision: an application that switches Peekaboot on deliberately outside a
     * local run must not have its own /actuator/env widened as a side effect.
     */
    @Test
    void doesNotShowActuatorValuesOutsideLocalDevelopment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("peekaboot.enabled", "true");

        postProcessor(false).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("management.endpoint.env.show-values"))
                .isNull();
        assertThat(environment.getProperty("management.endpoint.configprops.show-values"))
                .isNull();
    }

    /**
     * Absent, not an explicit "never": Peekaboot must not pin Spring's default into an
     * application that is not using it.
     */
    @Test
    void setsNoActuatorValueVisibilityWhenPeekabootIsDisabled() {
        MockEnvironment environment = new MockEnvironment();

        postProcessor(false).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("management.endpoint.env.show-values"))
                .isNull();
        assertThat(environment.getProperty("management.endpoint.configprops.show-values"))
                .isNull();
    }

    @Test
    void explicitShowValuesNeverWinsInLocalDevelopment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("management.endpoint.env.show-values", "never");

        postProcessor(true).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("management.endpoint.env.show-values"))
                .isEqualTo("never");
    }

    /**
     * Not a precedence proof like {@code explicitShowValuesNeverWinsInLocalDevelopment} -
     * outside local development the detection source never writes this key at all, so
     * nothing competes with the explicit value here. This only pins that an application's
     * own setting survives untouched.
     */
    @Test
    void explicitShowValuesAlwaysSurvivesOutsideLocalDevelopment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("management.endpoint.env.show-values", "always");

        postProcessor(false).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("management.endpoint.env.show-values"))
                .isEqualTo("always");
    }

    @Test
    void appPropertiesOverrideDefaults() {
        ConfigurableEnvironment environment = new MockEnvironment();

        // Simulate app properties with higher priority
        MapPropertySource appProperties =
                new MapPropertySource("appProperties", Map.of("management.info.java.enabled", "false"));
        environment.getPropertySources().addFirst(appProperties);

        postProcessor(true).postProcessEnvironment(environment, servletApplication());

        // App property should win over starter default
        assertThat(environment.getProperty("management.info.java.enabled")).isEqualTo("false");
    }

    @Test
    void defaultsHaveLowestPrecedence() {
        ConfigurableEnvironment environment = new MockEnvironment();

        postProcessor(true).postProcessEnvironment(environment, servletApplication());

        // Verify peekabootDefaults is added last (lowest priority)
        assertThat(environment.getPropertySources().get("peekabootDefaults")).isNotNull();

        // Add app properties after - they should still win
        MapPropertySource appProperties =
                new MapPropertySource("appProperties", Map.of("management.info.os.enabled", "false"));
        environment.getPropertySources().addFirst(appProperties);

        assertThat(environment.getProperty("management.info.os.enabled")).isEqualTo("false");
    }

    @Test
    void enablesTheDevToolbarByDefaultInLocalDevelopment() {
        MockEnvironment environment = new MockEnvironment();

        postProcessor(true).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("peekaboot.dev-toolbar", Boolean.class))
                .isTrue();
    }

    @Test
    void disablesTheDevToolbarByDefaultOutsideLocalDevelopment() {
        MockEnvironment environment = new MockEnvironment();

        postProcessor(false).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("peekaboot.dev-toolbar", Boolean.class))
                .isFalse();
    }

    /**
     * Switching Peekaboot on deliberately outside a local run must not also start injecting
     * the toolbar into every page - the toolbar default follows the launch context, not the
     * enabled flag.
     */
    @Test
    void explicitEnabledTrueOutsideLocalDevelopmentLeavesTheToolbarOff() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("peekaboot.enabled", "true");

        postProcessor(false).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("peekaboot.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("peekaboot.dev-toolbar", Boolean.class))
                .isFalse();
    }

    @Test
    void explicitDevToolbarFalseWinsInLocalDevelopment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("peekaboot.dev-toolbar", "false");

        postProcessor(true).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("peekaboot.dev-toolbar", Boolean.class))
                .isFalse();
    }

    @Test
    void explicitDevToolbarTrueWinsOutsideLocalDevelopment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("peekaboot.dev-toolbar", "true");

        postProcessor(false).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("peekaboot.dev-toolbar", Boolean.class))
                .isTrue();
    }

    @Test
    void enablesStorageByDefaultInLocalDevelopment() {
        MockEnvironment environment = new MockEnvironment();

        postProcessor(true).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("peekaboot.storage.enabled", Boolean.class))
                .isTrue();
    }

    @Test
    void disablesStorageByDefaultOutsideLocalDevelopment() {
        MockEnvironment environment = new MockEnvironment();

        postProcessor(false).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("peekaboot.storage.enabled", Boolean.class))
                .isFalse();
    }

    /**
     * Switching Peekaboot on deliberately outside a local run must not also start writing
     * files into that host's home directory - storage follows the launch context, not the
     * enabled flag.
     */
    @Test
    void explicitEnabledTrueOutsideLocalDevelopmentLeavesStorageOff() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("peekaboot.enabled", "true");

        postProcessor(false).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("peekaboot.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("peekaboot.storage.enabled", Boolean.class))
                .isFalse();
    }

    @Test
    void explicitStorageEnabledFalseWinsInLocalDevelopment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("peekaboot.storage.enabled", "false");

        postProcessor(true).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("peekaboot.storage.enabled", Boolean.class))
                .isFalse();
    }

    @Test
    void explicitStorageEnabledTrueWinsOutsideLocalDevelopment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("peekaboot.storage.enabled", "true");

        postProcessor(false).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("peekaboot.storage.enabled", Boolean.class))
                .isTrue();
    }

    @Test
    void shortensTheSpanExportDelayWhenTheToolbarIsOn() {
        MockEnvironment environment = new MockEnvironment();

        postProcessor(true).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("management.opentelemetry.tracing.export.schedule-delay"))
                .isEqualTo("200ms");
    }

    @Test
    void leavesTheSpanExportDelayAloneWhenTheToolbarIsOff() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("peekaboot.dev-toolbar", "false");

        postProcessor(true).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("management.opentelemetry.tracing.export.schedule-delay"))
                .isNull();
        assertThat(environment.getPropertySources().contains("peekabootDevToolbarDefaults"))
                .isFalse();
    }

    @Test
    void leavesTheSpanExportDelayAloneWhenPeekabootIsDisabled() {
        MockEnvironment environment = new MockEnvironment();

        postProcessor(false).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("management.opentelemetry.tracing.export.schedule-delay"))
                .isNull();
    }

    /**
     * Peekaboot's own defaults are skipped entirely once {@code peekaboot.enabled} resolves
     * false, before the dev-toolbar branch is ever reached - so an explicit
     * {@code peekaboot.dev-toolbar=true} does not decide this "regardless of
     * peekaboot.enabled"; it never gets read at all here.
     */
    @Test
    void leavesTheSpanExportDelayAloneWhenPeekabootIsDisabledEvenWithDevToolbarExplicitlyOn() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("peekaboot.enabled", "false");
        environment.setProperty("peekaboot.dev-toolbar", "true");

        postProcessor(true).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("management.opentelemetry.tracing.export.schedule-delay"))
                .isNull();
        assertThat(environment.getPropertySources().contains("peekabootDevToolbarDefaults"))
                .isFalse();
    }

    @Test
    void appPropertiesOverrideTheShortenedSpanExportDelay() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("management.opentelemetry.tracing.export.schedule-delay", "1s");

        postProcessor(true).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("management.opentelemetry.tracing.export.schedule-delay"))
                .isEqualTo("1s");
    }
}
