package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.peekaboot.backend.config.PeekabootProperties;
import org.peekaboot.backend.controller.PeekabootController;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.service.MetricsService;
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

class PeekabootAutoConfigurationTest {

    // PeekabootAutoConfiguration is @ConditionalOnWebApplication(SERVLET), so most of
    // this class exercises it through a servlet web application context; only
    // shouldNotRegisterBeansOnNonServletApplication uses the plain, non-servlet runner.
    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PeekabootAutoConfiguration.class))
            .withUserConfiguration(MockActuatorConfig.class);

    /**
     * The lifecycle switch is read by {@code PeekabootLifecycleAutoConfiguration}'s condition
     * before any Peekaboot bean exists, but it also binds here so it carries configuration
     * metadata and appears on the dashboard's own Config tab like every other switch.
     */
    @Test
    void bindsTheLifecycleSwitchDefaultingToOn() {
        contextRunner
                .withPropertyValues("peekaboot.enabled=true")
                .run(context -> assertThat(context.getBean(PeekabootProperties.class)
                                .getLifecycle()
                                .isEnabled())
                        .isTrue());
        contextRunner
                .withPropertyValues("peekaboot.enabled=true", "peekaboot.lifecycle.enabled=false")
                .run(context -> assertThat(context.getBean(PeekabootProperties.class)
                                .getLifecycle()
                                .isEnabled())
                        .isFalse());
    }

    @Test
    void shouldRegisterBeansWhenEnabledAndEndpointClassesPresent() {
        contextRunner.withPropertyValues("peekaboot.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PeekabootProperties.class);
            // Proves the explicit registrations actually wire up the whole
            // controller/service/mapper graph, not just the properties beans.
            assertThat(context).hasSingleBean(PeekabootController.class);
        });
    }

    /**
     * The contract behind every explicit {@code @Bean} carrying
     * {@code @ConditionalOnMissingBean}: an application that defines its own bean under an
     * auto-configured name wins, instead of the second definition raising
     * {@code BeanDefinitionOverrideException}. maskingEngine covers a bean this class has
     * always declared explicitly; metricsService covers the wider registered graph.
     */
    @Test
    void userSuppliedSameNamedBeansReplaceTheDefaults() {
        contextRunner
                .withPropertyValues("peekaboot.enabled=true")
                .withUserConfiguration(UserSameNamedBeansConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("maskingEngine")).isSameAs(UserSameNamedBeansConfig.MASKING_ENGINE);
                    assertThat(context.getBean("metricsService")).isSameAs(UserSameNamedBeansConfig.METRICS_SERVICE);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class UserSameNamedBeansConfig {

        static final MaskingEngine MASKING_ENGINE = new MaskingEngine();
        static final MetricsService METRICS_SERVICE = new MetricsService(null, MASKING_ENGINE);

        @Bean
        MaskingEngine maskingEngine() {
            return MASKING_ENGINE;
        }

        @Bean
        MetricsService metricsService() {
            return METRICS_SERVICE;
        }
    }

    @Test
    void shouldNotRegisterBeansWhenPeekabootDisabled() {
        contextRunner.withPropertyValues("peekaboot.enabled=false").run(context -> {
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
}
