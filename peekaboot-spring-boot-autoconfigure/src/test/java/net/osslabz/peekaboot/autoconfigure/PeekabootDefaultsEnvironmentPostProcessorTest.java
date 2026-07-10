package net.osslabz.peekaboot.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PeekabootDefaultsEnvironmentPostProcessorTest {

    private final PeekabootDefaultsEnvironmentPostProcessor postProcessor =
            new PeekabootDefaultsEnvironmentPostProcessor(java.util.function.Supplier::get);

    @Test
    void skipsDefaultsWhenPeekabootDisabled() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("peekaboot.enabled", "false");

        postProcessor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getPropertySources().contains("peekabootDefaults")).isFalse();
        assertThat(environment.getProperty("management.endpoint.health.show-details")).isNull();
    }

    @Test
    void loadsDefaultProperties() {
        ConfigurableEnvironment environment = new MockEnvironment();

        postProcessor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.endpoint.health.show-details"))
                .isEqualTo("always");
        assertThat(environment.getProperty("management.info.java.enabled"))
                .isEqualTo("true");
        assertThat(environment.getProperty("management.tracing.sampling.probability"))
                .isEqualTo("1.0");
    }

    @Test
    void appPropertiesOverrideDefaults() {
        ConfigurableEnvironment environment = new MockEnvironment();

        // Simulate app properties with higher priority
        MapPropertySource appProperties = new MapPropertySource("appProperties",
                Map.of("management.endpoint.health.show-details", "never"));
        environment.getPropertySources().addFirst(appProperties);

        postProcessor.postProcessEnvironment(environment, new SpringApplication());

        // App property should win over starter default
        assertThat(environment.getProperty("management.endpoint.health.show-details"))
                .isEqualTo("never");
    }

    @Test
    void defaultsHaveLowestPrecedence() {
        ConfigurableEnvironment environment = new MockEnvironment();

        postProcessor.postProcessEnvironment(environment, new SpringApplication());

        // Verify peekabootDefaults is added last (lowest priority)
        assertThat(environment.getPropertySources().get("peekabootDefaults")).isNotNull();

        // Add app properties after - they should still win
        MapPropertySource appProperties = new MapPropertySource("appProperties",
                Map.of("management.info.os.enabled", "false"));
        environment.getPropertySources().addFirst(appProperties);

        assertThat(environment.getProperty("management.info.os.enabled"))
                .isEqualTo("false");
    }
}
