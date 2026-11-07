package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.insights.InsightsService;
import org.peekaboot.backend.insights.web.InsightsController;
import org.peekaboot.backend.lifecycle.ApplicationStoppedListener;
import org.peekaboot.backend.lifecycle.web.LifecycleController;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;

/**
 * Component scanning names a host's own controller after its class. A Peekaboot controller under
 * that name stops the context with BeanDefinitionOverrideException, so every one takes the
 * peekaboot prefix.
 */
class PeekabootBeanNamesTest {

    private final WebApplicationContextRunner contextRunner =
            RegisteredAutoConfigurations.servletRunner().withPropertyValues("peekaboot.enabled=true");

    @Test
    void startsBesideHostControllersNamedInsightsAndLifecycleController() {
        try (LogCapture insightsStartup = LogCapture.attach(InsightsService.class);
                LogCapture stopBanner = LogCapture.attach(ApplicationStoppedListener.class)) {
            contextRunner.withUserConfiguration(HostControllersConfig.class).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean("insightsController")).isInstanceOf(HostInsightsController.class);
                assertThat(context.getBean("lifecycleController")).isInstanceOf(HostLifecycleController.class);
                assertThat(context).hasSingleBean(InsightsController.class);
                assertThat(context).hasSingleBean(LifecycleController.class);
            });
        }
    }

    /** A controller added later is held to the same rule. */
    @Test
    void everyPeekabootControllerTakesThePeekabootPrefix() {
        try (LogCapture insightsStartup = LogCapture.attach(InsightsService.class);
                LogCapture stopBanner = LogCapture.attach(ApplicationStoppedListener.class)) {
            contextRunner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBeansWithAnnotation(RestController.class).entrySet().stream()
                                .filter(bean ->
                                        bean.getValue().getClass().getName().startsWith("org.peekaboot."))
                                .map(Map.Entry::getKey))
                        .contains(
                                "peekabootInsightsController",
                                "peekabootLifecycleController",
                                "peekabootTraceController",
                                "peekabootApiController")
                        .allSatisfy(name -> assertThat(name).startsWith("peekaboot"));
            });
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class HostControllersConfig {

        @Bean
        HostInsightsController insightsController() {
            return new HostInsightsController();
        }

        @Bean
        HostLifecycleController lifecycleController() {
            return new HostLifecycleController();
        }
    }

    static class HostInsightsController {}

    static class HostLifecycleController {}
}
