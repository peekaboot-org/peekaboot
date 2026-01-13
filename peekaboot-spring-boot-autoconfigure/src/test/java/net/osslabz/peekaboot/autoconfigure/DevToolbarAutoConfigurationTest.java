package net.osslabz.peekaboot.autoconfigure;

import net.osslabz.peekaboot.backend.config.PeekabootProperties;
import net.osslabz.peekaboot.backend.devtoolbar.ToolbarDataProvider;
import net.osslabz.peekaboot.backend.service.PeekabookActuatorService;
import net.osslabz.peekaboot.tracing.autoconfigure.PeekabootTracingProperties;
import net.osslabz.peekaboot.tracing.event.InMemoryTraceEventBus;
import net.osslabz.peekaboot.tracing.event.TraceEventBus;
import net.osslabz.peekaboot.tracing.query.TraceQueryService;
import net.osslabz.peekaboot.tracing.store.InMemorySpanStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
                    assertThat(context).hasBean("devToolbarFilter");
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
        // Use a separate context runner without PeekabootAutoConfiguration
        // because its ComponentScan fails when TraceQueryService is filtered out
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
    void shouldCreateLogbackAppenderRegistrarWithEventBus() {
        contextRunner
                .withPropertyValues("peekaboot.dev-toolbar=true")
                .withUserConfiguration(MockTracingConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(DevToolbarAutoConfiguration.LogbackAppenderRegistrar.class);
                    assertThat(context).hasSingleBean(TraceEventBus.class);
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
        TraceQueryService traceQueryService() {
            return new TraceQueryService(new InMemorySpanStore(100, 50));
        }

        @Bean
        TraceEventBus traceEventBus() {
            return new InMemoryTraceEventBus();
        }
    }

    @Configuration
    @EnableConfigurationProperties(PeekabootProperties.class)
    static class MinimalPropertiesConfig {
    }
}
