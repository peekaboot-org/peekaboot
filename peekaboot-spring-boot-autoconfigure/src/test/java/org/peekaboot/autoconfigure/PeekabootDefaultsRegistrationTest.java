package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * Proves the EnvironmentPostProcessor is actually picked up by a real
 * SpringApplication bootstrap - context-runner tests bypass that registration
 * entirely, so a broken registration file stays invisible to them.
 */
class PeekabootDefaultsRegistrationTest {

    @Test
    void environmentPostProcessorRunsInRealSpringApplication() {
        try (ConfigurableApplicationContext context = application().run()) {
            assertThat(context.getEnvironment().getPropertySources().contains("peekabootDetection"))
                    .as("detection property source contributed during bootstrap")
                    .isTrue();
            // JUnit frames are on the stack, so detection must report "not local dev"
            assertThat(context.getEnvironment().getProperty("peekaboot.enabled", Boolean.class))
                    .isFalse();
        }
    }

    /**
     * Boot moves {@code SpringApplication.setDefaultProperties} below every other source
     * once the post-processors have run, so a source Peekaboot merely appends ends up above
     * them - and the detection would override the application's own default.
     */
    @Test
    void applicationDefaultPropertiesWinOverTheDetection() {
        SpringApplication application = application();
        application.setDefaultProperties(Map.of("peekaboot.enabled", "true"));

        try (ConfigurableApplicationContext context = application.run()) {
            assertThat(context.getEnvironment().getProperty("peekaboot.enabled", Boolean.class))
                    .isTrue();
        }
    }

    @Test
    void applicationDefaultPropertiesWinOverPeekabootsDefaults() {
        SpringApplication application = application();
        application.setDefaultProperties(Map.of("management.otlp.metrics.export.enabled", "true"));

        try (ConfigurableApplicationContext context = application.run()) {
            assertThat(context.getEnvironment().getProperty("management.otlp.metrics.export.enabled"))
                    .isEqualTo("true");
        }
    }

    private static SpringApplication application() {
        SpringApplication application = new SpringApplication(EmptyConfig.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        return application;
    }

    @Configuration
    static class EmptyConfig {}
}
