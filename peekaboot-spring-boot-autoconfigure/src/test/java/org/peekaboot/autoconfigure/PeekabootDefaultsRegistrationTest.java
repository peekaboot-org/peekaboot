package org.peekaboot.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the EnvironmentPostProcessor is actually picked up by a real
 * SpringApplication bootstrap - context-runner tests bypass that registration
 * entirely, so a broken registration file stays invisible to them.
 */
class PeekabootDefaultsRegistrationTest {

    @Test
    void environmentPostProcessorRunsInRealSpringApplication() {
        SpringApplication application = new SpringApplication(EmptyConfig.class);
        application.setWebApplicationType(WebApplicationType.NONE);

        try (ConfigurableApplicationContext context = application.run()) {
            assertThat(context.getEnvironment().getPropertySources().contains("peekabootDetection"))
                    .as("detection property source contributed during bootstrap")
                    .isTrue();
            // JUnit frames are on the stack, so detection must report "not local dev"
            assertThat(context.getEnvironment().getProperty("peekaboot.enabled", Boolean.class)).isFalse();
        }
    }

    @Configuration
    static class EmptyConfig {
    }
}
