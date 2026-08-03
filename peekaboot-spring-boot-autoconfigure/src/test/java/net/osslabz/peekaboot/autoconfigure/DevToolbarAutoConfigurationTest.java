package net.osslabz.peekaboot.autoconfigure;

import io.micrometer.tracing.Tracer;
import net.osslabz.peekaboot.backend.config.PeekabootProperties;
import net.osslabz.peekaboot.backend.devtoolbar.ToolbarDataProvider;
import net.osslabz.peekaboot.backend.service.PeekabootActuatorService;
import net.osslabz.peekaboot.backend.tracing.autoconfigure.PeekabootTracingProperties;
import net.osslabz.peekaboot.backend.tracing.query.TraceQueryService;
import net.osslabz.peekaboot.backend.tracing.store.InMemoryTraceStore;
import net.osslabz.peekaboot.backend.tracing.store.TraceStore;
import net.osslabz.peekaboot.backend.tracing.store.TraceStoreEventListener;
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
    void logbackAppenderIsDetachedWhenContextCloses() {
        int before = peekabootAppenderCount();

        contextRunner
                .withPropertyValues("peekaboot.dev-toolbar=true")
                .withUserConfiguration(MockTracingConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(DevToolbarAutoConfiguration.LogbackAppenderRegistrar.class);
                    assertThat(peekabootAppenderCount())
                            .as("appender attached while context runs")
                            .isEqualTo(before + 1);
                });

        // run() closed the context; a devtools restart must not accumulate appenders
        assertThat(peekabootAppenderCount())
                .as("appender detached after context close")
                .isEqualTo(before);
    }

    @Test
    void shouldNotCreateLogbackRegistrarWhenLogbackMissing() {
        contextRunner
                .withPropertyValues("peekaboot.dev-toolbar=true")
                .withUserConfiguration(MockTracingConfig.class)
                .withClassLoader(new FilteredClassLoader(ch.qos.logback.classic.LoggerContext.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(DevToolbarAutoConfiguration.LogbackAppenderRegistrar.class);
                });
    }

    private int peekabootAppenderCount() {
        ch.qos.logback.classic.LoggerContext loggerContext =
                (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger root =
                loggerContext.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        int count = 0;
        java.util.Iterator<ch.qos.logback.core.Appender<ch.qos.logback.classic.spi.ILoggingEvent>> it =
                root.iteratorForAppenders();
        while (it.hasNext()) {
            if (it.next() instanceof net.osslabz.peekaboot.backend.log.PeekabootLogbackAppender) {
                count++;
            }
        }
        return count;
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
        PeekabootActuatorService peekabootActuatorService() {
            return mock(PeekabootActuatorService.class);
        }
    }

    @Configuration
    @EnableConfigurationProperties(PeekabootTracingProperties.class)
    static class MockTracingConfig {
        @Bean
        TraceStore traceStore() {
            return new InMemoryTraceStore(100, 50, Duration.ofMinutes(5));
        }

        @Bean
        TraceStoreEventListener traceStoreEventListener(TraceStore traceStore) {
            return new TraceStoreEventListener(traceStore);
        }

        @Bean
        TraceQueryService traceQueryService(TraceStore store) {
            return new TraceQueryService((InMemoryTraceStore) store);
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
