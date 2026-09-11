package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.mock.env.MockEnvironment;

class PeekabootDefaultsEnvironmentPostProcessorTest {

    /**
     * Real detection inspects the launch context (thread, classloader, stack),
     * which inside a JUnit run always reads "not local dev"; the override pins
     * the detection result so both branches are testable.
     */
    private PeekabootDefaultsEnvironmentPostProcessor postProcessor(boolean localDevelopment) {
        return postProcessor(
                localDevelopment ? LocalDevDetector.LaunchKind.LOCAL_DEV : LocalDevDetector.LaunchKind.DEPLOYMENT);
    }

    private PeekabootDefaultsEnvironmentPostProcessor postProcessor(LocalDevDetector.LaunchKind launchKind) {
        return new PeekabootDefaultsEnvironmentPostProcessor(Supplier::get) {
            @Override
            LocalDevDetector.LaunchKind launchKind() {
                return launchKind;
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

    /**
     * The three switches follow the launch detection unless set explicitly, and each on its
     * own: switching Peekaboot on deliberately outside a local run neither injects the toolbar
     * into every page nor writes files into that host's home directory, and switching it off
     * locally leaves the other two where detection put them.
     */
    @ParameterizedTest(name = "local={0} enabled={1} dev-toolbar={2} storage={3}")
    @CsvSource(
            nullValues = "-",
            value = {
                // local, explicit enabled, explicit dev-toolbar, explicit storage -> enabled, dev-toolbar, storage
                "true,  -,     -,     -,     true,  true,  true",
                "false, -,     -,     -,     false, false, false",
                "false, true,  -,     -,     true,  false, false",
                "true,  false, -,     -,     false, true,  true",
                "true,  -,     false, -,     true,  false, true",
                "false, -,     true,  -,     false, true,  false",
                "true,  -,     -,     false, true,  true,  false",
                "false, -,     -,     true,  false, false, true"
            })
    void theSwitchesFollowDetectionUnlessSetExplicitly(
            boolean localDevelopment,
            Boolean enabled,
            Boolean devToolbar,
            Boolean storage,
            boolean expectedEnabled,
            boolean expectedDevToolbar,
            boolean expectedStorage) {
        MockEnvironment environment = new MockEnvironment();
        setIfGiven(environment, "peekaboot.enabled", enabled);
        setIfGiven(environment, "peekaboot.dev-toolbar", devToolbar);
        setIfGiven(environment, "peekaboot.storage.enabled", storage);

        postProcessor(localDevelopment).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("peekaboot.enabled", Boolean.class)).isEqualTo(expectedEnabled);
        assertThat(environment.getProperty("peekaboot.dev-toolbar", Boolean.class))
                .isEqualTo(expectedDevToolbar);
        assertThat(environment.getProperty("peekaboot.storage.enabled", Boolean.class))
                .isEqualTo(expectedStorage);
    }

    /** The observability defaults come and go with the resolved switch, not with the detection. */
    @ParameterizedTest(name = "local={0} enabled={1}")
    @CsvSource(
            nullValues = "-",
            value = {"true, -, true", "false, -, false", "false, true, true", "true, false, false"})
    void theObservabilityDefaultsFollowTheResolvedEnabledSwitch(
            boolean localDevelopment, Boolean enabled, boolean expectDefaults) {
        MockEnvironment environment = new MockEnvironment();
        setIfGiven(environment, "peekaboot.enabled", enabled);

        postProcessor(localDevelopment).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getPropertySources().contains("peekabootDefaults"))
                .isEqualTo(expectDefaults);
        assertThat(environment.getProperty("management.info.java.enabled") != null)
                .isEqualTo(expectDefaults);
    }

    private static void setIfGiven(MockEnvironment environment, String key, Boolean value) {
        if (value != null) {
            environment.setProperty(key, value.toString());
        }
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
     * Peekaboot reads no Logbook output and no datasource-proxy log lines (query text comes
     * from span tags), so it configures neither library; and it does not restate Spring's
     * own defaults. Only what the dashboard consumes is defaulted.
     */
    @Test
    void configuresNoThirdPartyLoggingAndRestatesNoSpringDefault() {
        MockEnvironment environment = new MockEnvironment();

        postProcessor(true).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("spring.jpa.properties[hibernate.generate_statistics]"))
                .isEqualTo("true");
        assertThat(environment.getProperty("decorator.datasource.datasource-proxy.format-sql"))
                .isNull();
        assertThat(environment.getProperty("decorator.datasource.datasource-proxy.query.log-level"))
                .isNull();
        assertThat(environment.getProperty("logbook.format.style")).isNull();
        assertThat(environment.getProperty("logbook.strategy")).isNull();
        assertThat(environment.getProperty("management.info.git.enabled")).isNull();
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
     * only cost. Detection (the activation switches, storage) and the no-push default
     * still apply: they gate the lifecycle and storage beans, which are not
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

    /**
     * Peekaboot reads {@code env} and {@code configprops} through endpoints it constructs
     * itself (see {@code ActuatorSourcesAutoConfiguration}), so it has no reason to decide
     * value visibility for the application's own actuator - and must not, on a local run
     * or anywhere else.
     */
    @Test
    void leavesActuatorValueVisibilityEntirelyToTheApplication() {
        ConfigurableEnvironment environment = new MockEnvironment();

        postProcessor(true).postProcessEnvironment(environment, servletApplication());

        EnumerablePropertySource<?> detection =
                (EnumerablePropertySource<?>) environment.getPropertySources().get("peekabootDetection");
        assertThat(detection.getPropertyNames())
                .containsExactlyInAnyOrder(
                        PeekabootPropertyKeys.ENABLED,
                        PeekabootPropertyKeys.DEV_TOOLBAR,
                        PeekabootPropertyKeys.STORAGE_ENABLED,
                        PeekabootPropertyKeys.SECURITY_ENABLED,
                        PeekabootPropertyKeys.SECURITY_DEPLOYMENT_DETECTED);
        assertThat(environment.getProperty("management.endpoint.env.show-values"))
                .isNull();
        assertThat(environment.getProperty("management.endpoint.configprops.show-values"))
                .isNull();
    }

    @Test
    void securityIsEnabledByDefaultOnADeploymentLaunch() {
        ConfigurableEnvironment environment = new MockEnvironment();

        postProcessor(LocalDevDetector.LaunchKind.DEPLOYMENT).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("peekaboot.security.enabled", Boolean.class))
                .isTrue();
    }

    @Test
    void securityIsOffByDefaultOnALocalDevLaunch() {
        ConfigurableEnvironment environment = new MockEnvironment();

        postProcessor(LocalDevDetector.LaunchKind.LOCAL_DEV).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("peekaboot.security.enabled", Boolean.class))
                .isFalse();
    }

    /** Without this a @SpringBootTest in a consumer's build would start getting 401s. */
    @Test
    void securityIsOffByDefaultOnATestLaunch() {
        ConfigurableEnvironment environment = new MockEnvironment();

        postProcessor(LocalDevDetector.LaunchKind.TEST).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("peekaboot.security.enabled", Boolean.class))
                .isFalse();
    }

    @Test
    void anExplicitSecuritySettingBeatsTheDetectedDefault() {
        ConfigurableEnvironment environment = new MockEnvironment();
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource("appProperties", Map.of("peekaboot.security.enabled", "false")));

        postProcessor(LocalDevDetector.LaunchKind.DEPLOYMENT).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("peekaboot.security.enabled", Boolean.class))
                .isFalse();
        assertThat(environment
                        .getPropertySources()
                        .get(PeekabootPropertyKeys.DETECTION_PROPERTY_SOURCE_NAME)
                        .getProperty("peekaboot.security.enabled"))
                .isEqualTo(true);
        // unlike the switch above, this one is never overridden - PeekabootSecurityAutoConfiguration
        // reads it to tell this exact case (an explicit false on a real deployment) from a local
        // or test launch, which the resolved peekaboot.security.enabled alone cannot answer
        assertThat(environment.getProperty(PeekabootPropertyKeys.SECURITY_DEPLOYMENT_DETECTED, Boolean.class))
                .isTrue();
    }

    /**
     * The detection source's own name disappears once Boot's {@code defaultProperties} already
     * exists (see {@link #foldsItsDefaultsUnderneathTheApplicationsDefaultProperties}) - the
     * deployment signal has to survive that fold regardless, since
     * {@code PeekabootSecurityAutoConfiguration} reads it directly off the {@code Environment}
     * rather than by the source's name.
     */
    @Test
    void theDetectedDeploymentSignalSurvivesTheFoldIntoDefaultProperties() {
        ConfigurableEnvironment environment = new MockEnvironment();
        environment
                .getPropertySources()
                .addLast(new DefaultPropertiesPropertySource(Map.of("peekaboot.security.enabled", "false")));

        postProcessor(LocalDevDetector.LaunchKind.DEPLOYMENT).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getPropertySources().contains(PeekabootPropertyKeys.DETECTION_PROPERTY_SOURCE_NAME))
                .isFalse();
        assertThat(environment.getProperty("peekaboot.security.enabled", Boolean.class))
                .isFalse();
        assertThat(environment.getProperty(PeekabootPropertyKeys.SECURITY_DEPLOYMENT_DETECTED, Boolean.class))
                .isTrue();
    }

    @Test
    void appPropertiesOverrideDefaults() {
        ConfigurableEnvironment environment = new MockEnvironment();
        MapPropertySource appProperties =
                new MapPropertySource("appProperties", Map.of("management.info.java.enabled", "false"));
        environment.getPropertySources().addFirst(appProperties);

        postProcessor(true).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("management.info.java.enabled")).isEqualTo("false");
    }

    @Test
    void defaultsHaveLowestPrecedence() {
        ConfigurableEnvironment environment = new MockEnvironment();

        postProcessor(true).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getPropertySources().stream().map(PropertySource::getName))
                .endsWith(
                        "peekabootDetection",
                        "peekabootNoPushDefaults",
                        "peekabootDefaults",
                        "peekabootDevToolbarDefaults");
    }

    /**
     * Boot keeps {@code defaultProperties} the last source whatever a post-processor appends
     * after it, so Peekaboot's entries have to go inside that source - underneath the
     * application's own, which keep winning on overlap.
     */
    @Test
    void foldsItsDefaultsUnderneathTheApplicationsDefaultProperties() {
        ConfigurableEnvironment environment = new MockEnvironment();
        environment
                .getPropertySources()
                .addLast(new DefaultPropertiesPropertySource(Map.of("peekaboot.enabled", "false")));

        postProcessor(true).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getPropertySources().stream().map(PropertySource::getName))
                .endsWith(DefaultPropertiesPropertySource.NAME);
        assertThat(environment.getProperty("peekaboot.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("peekaboot.storage.enabled", Boolean.class))
                .isTrue();
        assertThat(environment.getProperty("management.otlp.metrics.export.enabled"))
                .isEqualTo("false");
    }

    /**
     * An application that keeps spring-webmvc on the classpath but opts out of the web server
     * in its properties is still deduced SERVLET when the post-processors run; the property
     * decides, as it does for the auto-configurations.
     */
    @Test
    void honoursAnExplicitNonWebTypeOverTheDeducedOne() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.main.web-application-type", "none");

        postProcessor(true).postProcessEnvironment(environment, servletApplication());

        assertThat(environment.getProperty("peekaboot.enabled", Boolean.class)).isTrue();
        assertThat(environment.getPropertySources().contains("peekabootDefaults"))
                .isFalse();
        assertThat(environment.getProperty("management.endpoint.env.show-values"))
                .isNull();
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

    /**
     * A bundled file can only be missing when a consumer's shade or repackage step filtered
     * it out. For the no-push defaults that would silently start pushing telemetry to
     * localhost, the one thing the file exists to prevent, so a warning is the wrong answer.
     */
    @Test
    void failsFastWhenABundledDefaultsFileIsMissing() {
        PeekabootDefaultsEnvironmentPostProcessor postProcessor =
                postProcessorReading(resourceName -> new ClassPathResource("filtered-out/" + resourceName));

        assertThatThrownBy(() -> postProcessor.postProcessEnvironment(new MockEnvironment(), servletApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("peekaboot-no-push-defaults.yml");
    }

    @Test
    void failsFastWhenABundledDefaultsFileIsEmpty() {
        PeekabootDefaultsEnvironmentPostProcessor postProcessor =
                postProcessorReading(resourceName -> new ByteArrayResource(new byte[0]));

        assertThatThrownBy(() -> postProcessor.postProcessEnvironment(new MockEnvironment(), servletApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("peekaboot-no-push-defaults.yml");
    }

    /** The seam for the two tests above: what the post-processor finds under a bundled file's name. */
    private static PeekabootDefaultsEnvironmentPostProcessor postProcessorReading(
            Function<String, Resource> bundledDefaults) {
        return new PeekabootDefaultsEnvironmentPostProcessor(Supplier::get) {
            @Override
            LocalDevDetector.LaunchKind launchKind() {
                return LocalDevDetector.LaunchKind.DEPLOYMENT;
            }

            @Override
            Resource bundledDefaults(String resourceName) {
                return bundledDefaults.apply(resourceName);
            }
        };
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
