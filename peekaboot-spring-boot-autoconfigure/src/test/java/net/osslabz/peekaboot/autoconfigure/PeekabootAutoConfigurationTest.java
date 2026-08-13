package net.osslabz.peekaboot.autoconfigure;

import net.osslabz.peekaboot.backend.config.PeekabootProperties;
import net.osslabz.peekaboot.backend.controller.PeekabootController;
import net.osslabz.peekaboot.backend.service.PeekabootActuatorService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PeekabootAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PeekabootAutoConfiguration.class))
            .withUserConfiguration(MockActuatorConfig.class);

    @Test
    void propertiesHaveCorrectDefaults() {
        PeekabootProperties properties = new PeekabootProperties();
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.isDevToolbar()).isFalse();
    }

    @Test
    void propertiesCanBeModified() {
        PeekabootProperties properties = new PeekabootProperties();
        properties.setEnabled(false);
        properties.setDevToolbar(true);

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.isDevToolbar()).isTrue();
    }

    @Test
    void shouldRegisterBeansWhenEnabledAndEndpointClassesPresent() {
        contextRunner
                .withPropertyValues("peekaboot.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PeekabootProperties.class);
                    // Proves the @ComponentScan actually pulls in the controller/service/
                    // mapper/config/actuator packages, not just the properties beans.
                    assertThat(context).hasSingleBean(PeekabootController.class);
                });
    }

    @Test
    void shouldNotRegisterBeansWhenPeekabootDisabled() {
        contextRunner
                .withPropertyValues("peekaboot.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PeekabootController.class);
                    assertThat(context).doesNotHaveBean(PeekabootProperties.class);
                });
    }

    @Test
    void shouldRegisterBeansWhenEnabledPropertyMissing() {
        // matchIfMissing = true
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PeekabootController.class);
        });
    }

    @Test
    void shouldNotRegisterBeansWhenHealthEndpointClassMissing() {
        contextRunner
                .withClassLoader(new FilteredClassLoader(HealthEndpoint.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PeekabootController.class);
                });
    }

    @Test
    void shouldNotRegisterBeansWhenInfoEndpointClassMissing() {
        contextRunner
                .withClassLoader(new FilteredClassLoader(InfoEndpoint.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PeekabootController.class);
                });
    }

    @Configuration
    static class MockActuatorConfig {
        @Bean
        PeekabootActuatorService peekabootActuatorService() {
            return mock(PeekabootActuatorService.class);
        }
    }
}
