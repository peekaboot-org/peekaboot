package net.osslabz.peekaboot.autoconfigure;

import io.micrometer.tracing.Tracer;
import net.osslabz.peekaboot.backend.config.PeekabootProperties;
import net.osslabz.peekaboot.backend.devtoolbar.ToolbarDataProvider;
import net.osslabz.peekaboot.backend.service.PeekabookActuatorService;
import net.osslabz.peekaboot.backend.tracing.autoconfigure.PeekabootTracingProperties;
import net.osslabz.peekaboot.backend.tracing.query.TraceQueryService;
import net.osslabz.peekaboot.backend.tracing.store.TraceDataStorage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DevToolbarAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DevToolbarAutoConfiguration.class,
                    PeekabootAutoConfiguration.class
            ))
            .withUserConfiguration(MockActuatorConfig.class);

    @Test
    void shouldCreateBeansWhenDevToolbarEnabled() {
        contextRunner
                .withPropertyValues("peekaboot.dev-toolbar=true")
                .withUserConfiguration(MockTracingConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(ToolbarDataProvider.class);
                    assertThat(context).hasSingleBean(DevToolbarAutoConfiguration.LogbackAppenderRegistrar.class);
                });
    }

    @Test
    void shouldNotCreateBeansWhenDevToolbarDisabled() {
        contextRunner
                .withPropertyValues("peekaboot.dev-toolbar=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ToolbarDataProvider.class);
                    assertThat(context).doesNotHaveBean("devToolbarFilter");
                });
    }

    @Test
    void shouldNotCreateBeansWhenDevToolbarPropertyMissing() {
        contextRunner
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ToolbarDataProvider.class);
                    assertThat(context).doesNotHaveBean("devToolbarFilter");
                });
    }

    @Test
    void shouldNotCreateBeansWhenTracingNotOnClasspath() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DevToolbarAutoConfiguration.class))
                .withUserConfiguration(MinimalPropertiesConfig.class)
                .withPropertyValues("peekaboot.dev-toolbar=true")
                .withClassLoader(new FilteredClassLoader(TraceQueryService.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ToolbarDataProvider.class);
                    assertThat(context).doesNotHaveBean("devToolbarFilter");
                });
    }

    @Test
    void peekabootDisabledWinsOverDevToolbarFlag() {
        // peekaboot.enabled=false skips PeekabootAutoConfiguration (and with it
        // the PeekabootProperties bean); the toolbar must switch off cleanly
        // instead of failing the context on the missing bean.
        contextRunner
                .withPropertyValues("peekaboot.enabled=false", "peekaboot.dev-toolbar=true")
                .withUserConfiguration(MockTracingConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ToolbarDataProvider.class);
                    assertThat(context).doesNotHaveBean("devToolbarFilter");
                });
    }

    @Test
    void shouldCreateLogbackAppenderRegistrar() {
        contextRunner
                .withPropertyValues("peekaboot.dev-toolbar=true")
                .withUserConfiguration(MockTracingConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(DevToolbarAutoConfiguration.LogbackAppenderRegistrar.class);
                });
    }

    @Test
    void propertiesDefaultDevToolbarToFalse() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(PeekabootProperties.class);
            PeekabootProperties props = context.getBean(PeekabootProperties.class);
            assertThat(props.isDevToolbar()).isFalse();
        });
    }

    @Configuration
    static class MockActuatorConfig {
        @Bean
        PeekabookActuatorService peekabookActuatorService() {
            return mock(PeekabookActuatorService.class);
        }
    }

    @Configuration
    @EnableConfigurationProperties(PeekabootTracingProperties.class)
    static class MockTracingConfig {
        @Bean
        TraceDataStorage traceDataStorage() {
            return new TraceDataStorage(100, 50, Duration.ofMinutes(5));
        }

        @Bean
        TraceQueryService traceQueryService(TraceDataStorage storage) {
            return new TraceQueryService(storage);
        }

        @Bean
        Tracer tracer() {
            return mock(Tracer.class);
        }
    }

    @Configuration
    @EnableConfigurationProperties(PeekabootProperties.class)
    static class MinimalPropertiesConfig {
    }
}
