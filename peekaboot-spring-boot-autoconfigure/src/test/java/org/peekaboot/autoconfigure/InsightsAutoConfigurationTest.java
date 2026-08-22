package org.peekaboot.autoconfigure;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.insights.InsightsService;
import org.peekaboot.backend.insights.web.InsightsController;
import org.peekaboot.backend.insights.web.InsightsSsePublisher;
import org.peekaboot.backend.service.PeekabootActuatorService;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class InsightsAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PeekabootAutoConfiguration.class, InsightsAutoConfiguration.class, JacksonAutoConfiguration.class))
            .withUserConfiguration(MockActuatorConfig.class)
            .withPropertyValues("peekaboot.enabled=true");

    @Test
    void activatesWithMeterRegistryAndPeekabootEnabled() {
        contextRunner
                .withUserConfiguration(MeterRegistryConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(InsightsService.class);
                    assertThat(context).hasSingleBean(InsightsSsePublisher.class);
                    assertThat(context).hasSingleBean(InsightsController.class);
                });
    }

    @Test
    void backsOffWithoutMeterRegistry() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(InsightsService.class);
        });
    }

    @Test
    void backsOffWhenInsightsDisabled() {
        contextRunner
                .withUserConfiguration(MeterRegistryConfig.class)
                .withPropertyValues("peekaboot.insights.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(InsightsService.class);
                });
    }

    @Test
    void backsOffWhenPeekabootDisabled() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        PeekabootAutoConfiguration.class, InsightsAutoConfiguration.class, JacksonAutoConfiguration.class))
                .withUserConfiguration(MockActuatorConfig.class, MeterRegistryConfig.class)
                .withPropertyValues("peekaboot.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(InsightsService.class);
                });
    }

    @Configuration
    static class MeterRegistryConfig {
        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Configuration
    static class MockActuatorConfig {
        @Bean
        PeekabootActuatorService peekabootActuatorService() {
            return mock(PeekabootActuatorService.class);
        }
    }
}
