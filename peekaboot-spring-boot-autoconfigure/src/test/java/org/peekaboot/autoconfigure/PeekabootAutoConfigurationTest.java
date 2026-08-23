package org.peekaboot.autoconfigure;

import org.peekaboot.backend.config.PeekabootProperties;
import org.peekaboot.backend.controller.PeekabootController;
import org.peekaboot.backend.service.PeekabootActuatorService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PeekabootAutoConfigurationTest {

    // PeekabootAutoConfiguration is now @ConditionalOnWebApplication(SERVLET), so most of
    // this class exercises it through a servlet web application context; only
    // shouldNotRegisterBeansOnNonServletApplication uses the plain, non-servlet runner.
    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
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
    void shouldNotRegisterBeansWhenEnabledPropertyMissing() {
        // matchIfMissing = false: without the environment post-processor's detected
        // default the safe fallback is off
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(PeekabootController.class);
        });
    }

    @Test
    void shouldNotRegisterBeansOnNonServletApplication() {
        // peekaboot.enabled=true is the default in local development; on a reactive or
        // non-web application PeekabootAutoConfiguration must stay inactive rather than
        // partially activating a servlet-only component (PeekabootWebConfig).
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PeekabootAutoConfiguration.class))
                .withUserConfiguration(MockActuatorConfig.class)
                .withPropertyValues("peekaboot.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PeekabootController.class);
                    assertThat(context).doesNotHaveBean(PeekabootProperties.class);
                });
    }

    @Test
    void shouldNotRegisterBeansWhenSpringWebmvcAbsentEntirely() {
        // shouldNotRegisterBeansOnNonServletApplication only proves the guard works when
        // spring-webmvc is present but simply unused by a plain, non-web context — this
        // module's own test classpath always carries spring-webmvc. A genuinely reactive
        // application doesn't have spring-webmvc on the classpath at all, so hide
        // WebMvcConfigurer (and the registry types PeekabootWebConfig references) on a
        // reactive web context to simulate that absence for real: the context must still
        // start cleanly and register no Peekaboot beans, rather than failing to load
        // PeekabootWebConfig with a NoClassDefFoundError.
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PeekabootAutoConfiguration.class))
                .withUserConfiguration(MockActuatorConfig.class)
                .withClassLoader(new FilteredClassLoader(
                        WebMvcConfigurer.class, ResourceHandlerRegistry.class, ViewControllerRegistry.class))
                .withPropertyValues("peekaboot.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PeekabootController.class);
                    assertThat(context).doesNotHaveBean(PeekabootProperties.class);
                });
    }

    @Test
    void shouldNotRegisterBeansWhenHealthEndpointClassMissing() {
        contextRunner
                .withPropertyValues("peekaboot.enabled=true")
                .withClassLoader(new FilteredClassLoader(HealthEndpoint.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PeekabootController.class);
                });
    }

    @Test
    void shouldNotRegisterBeansWhenInfoEndpointClassMissing() {
        contextRunner
                .withPropertyValues("peekaboot.enabled=true")
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
