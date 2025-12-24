package net.osslabz.peekaboot.autoconfigure;

import net.osslabz.peekaboot.backend.controller.PeekabootController;
import net.osslabz.peekaboot.backend.service.PeekabootService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PeekabootAutoConfigurationTest {

    @Configuration
    static class TestConfiguration {
        @Bean
        public InfoEndpoint infoEndpoint() {
            InfoEndpoint mock = mock(InfoEndpoint.class);
            when(mock.info()).thenReturn(Collections.emptyMap());
            return mock;
        }

        @Bean
        public HealthEndpoint healthEndpoint() {
            HealthEndpoint mock = mock(HealthEndpoint.class);
            when(mock.health()).thenReturn(Health.up().build());
            return mock;
        }
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PeekabootAutoConfiguration.class,
                    PeekabootLifecycleAutoConfiguration.class
            ))
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues("peekaboot.lifecycle.enabled=false");

    @Test
    void autoConfiguration_shouldCreateBeansWhenActuatorIsPresent() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(PeekabootService.class);
            assertThat(context).hasSingleBean(PeekabootController.class);
            assertThat(context).hasSingleBean(PeekabootProperties.class);
        });
    }

    @Test
    void autoConfiguration_shouldNotCreateBeansWhenDisabled() {
        contextRunner
                .withPropertyValues("peekaboot.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(PeekabootService.class);
                    assertThat(context).doesNotHaveBean(PeekabootController.class);
                });
    }

    @Test
    void autoConfiguration_shouldRespectCustomBasePath() {
        contextRunner
                .withPropertyValues("peekaboot.basePath=/custom")
                .run(context -> {
                    assertThat(context).hasSingleBean(PeekabootProperties.class);
                    PeekabootProperties properties = context.getBean(PeekabootProperties.class);
                    assertThat(properties.getBasePath()).isEqualTo("/custom");
                });
    }

    @Test
    void autoConfiguration_shouldUseDefaultBasePath() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(PeekabootProperties.class);
            PeekabootProperties properties = context.getBean(PeekabootProperties.class);
            assertThat(properties.getBasePath()).isEqualTo("/peekaboot");
        });
    }

    @Test
    void autoConfiguration_shouldBeEnabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(PeekabootProperties.class);
            PeekabootProperties properties = context.getBean(PeekabootProperties.class);
            assertThat(properties.isEnabled()).isTrue();
        });
    }
}
