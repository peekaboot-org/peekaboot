package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
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

    @Test
    void enablesPeekabootByDefaultInLocalDevelopment() {
        MockEnvironment environment = new MockEnvironment();

        postProcessor(true).postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("peekaboot.enabled", Boolean.class)).isTrue();
    }

    @Test
    void disablesPeekabootByDefaultOutsideLocalDevelopment() {
        MockEnvironment environment = new MockEnvironment();

        postProcessor(false).postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("peekaboot.enabled", Boolean.class)).isFalse();
        assertThat(environment.getPropertySources().contains("peekabootDefaults"))
                .isFalse();
        assertThat(environment.getProperty("management.endpoint.health.show-details"))
                .isNull();
    }

    @Test
    void explicitEnabledTrueWinsOverDetection() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("peekaboot.enabled", "true");

        postProcessor(false).postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("peekaboot.enabled", Boolean.class)).isTrue();
        assertThat(environment.getPropertySources().contains("peekabootDefaults"))
                .isTrue();
    }

    @Test
    void explicitEnabledFalseWinsOverDetection() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("peekaboot.enabled", "false");

        postProcessor(true).postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("peekaboot.enabled", Boolean.class)).isFalse();
        assertThat(environment.getPropertySources().contains("peekabootDefaults"))
                .isFalse();
        assertThat(environment.getProperty("management.endpoint.health.show-details"))
                .isNull();
    }

    @Test
    void disablesOtlpMetricsExportWhenPeekabootEnabled() {
        MockEnvironment environment = new MockEnvironment();

        postProcessor(true).postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.otlp.metrics.export.enabled"))
                .isEqualTo("false");
    }

    @Test
    void disablesOtlpMetricsExportEvenWhenPeekabootDisabled() {
        MockEnvironment environment = new MockEnvironment();

        postProcessor(false).postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.otlp.metrics.export.enabled"))
                .isEqualTo("false");
    }

    @Test
    void appPropertiesCanReenableOtlpMetricsExport() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("management.otlp.metrics.export.enabled", "true");

        postProcessor(false).postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.otlp.metrics.export.enabled"))
                .isEqualTo("true");
    }

    @Test
    void loadsDefaultProperties() {
        ConfigurableEnvironment environment = new MockEnvironment();

        postProcessor(true).postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.endpoint.health.show-details"))
                .isEqualTo("always");
        assertThat(environment.getProperty("management.info.java.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("management.tracing.sampling.probability"))
                .isEqualTo("1.0");
    }

    @Test
    void showsActuatorValuesSoTheEnvironmentAndConfigTabsAreReadable() {
        ConfigurableEnvironment environment = new MockEnvironment();

        postProcessor(true).postProcessEnvironment(environment, new SpringApplication());

        // Spring's default is "never", which masks every value - not just secret ones -
        // and leaves the dashboard's Environment and Config tabs showing only "******"
        assertThat(environment.getProperty("management.endpoint.env.show-values"))
                .isEqualTo("always");
        assertThat(environment.getProperty("management.endpoint.configprops.show-values"))
                .isEqualTo("always");
    }

    @Test
    void appPropertiesOverrideDefaults() {
        ConfigurableEnvironment environment = new MockEnvironment();

        // Simulate app properties with higher priority
        MapPropertySource appProperties =
                new MapPropertySource("appProperties", Map.of("management.endpoint.health.show-details", "never"));
        environment.getPropertySources().addFirst(appProperties);

        postProcessor(true).postProcessEnvironment(environment, new SpringApplication());

        // App property should win over starter default
        assertThat(environment.getProperty("management.endpoint.health.show-details"))
                .isEqualTo("never");
    }

    @Test
    void defaultsHaveLowestPrecedence() {
        ConfigurableEnvironment environment = new MockEnvironment();

        postProcessor(true).postProcessEnvironment(environment, new SpringApplication());

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

        postProcessor(true).postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("peekaboot.dev-toolbar", Boolean.class))
                .isTrue();
    }

    @Test
    void disablesTheDevToolbarByDefaultOutsideLocalDevelopment() {
        MockEnvironment environment = new MockEnvironment();

        postProcessor(false).postProcessEnvironment(environment, new SpringApplication());

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

        postProcessor(false).postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("peekaboot.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("peekaboot.dev-toolbar", Boolean.class))
                .isFalse();
    }

    @Test
    void explicitDevToolbarFalseWinsInLocalDevelopment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("peekaboot.dev-toolbar", "false");

        postProcessor(true).postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("peekaboot.dev-toolbar", Boolean.class))
                .isFalse();
    }

    @Test
    void explicitDevToolbarTrueWinsOutsideLocalDevelopment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("peekaboot.dev-toolbar", "true");

        postProcessor(false).postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("peekaboot.dev-toolbar", Boolean.class))
                .isTrue();
    }
}
