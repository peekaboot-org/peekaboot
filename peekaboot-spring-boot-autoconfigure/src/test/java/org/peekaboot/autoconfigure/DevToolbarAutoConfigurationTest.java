package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.util.ContextInitializer;
import ch.qos.logback.core.Appender;
import io.micrometer.tracing.Tracer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.config.PeekabootProperties;
import org.peekaboot.backend.devtoolbar.ToolbarDataProvider;
import org.peekaboot.backend.filter.DevToolbarFilter;
import org.peekaboot.backend.log.PeekabootLogbackAppender;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.peekaboot.backend.tracing.store.TraceStoreEventListener;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class DevToolbarAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DevToolbarAutoConfiguration.class,
                    PeekabootAutoConfiguration.class,
                    PeekabootPathsAutoConfiguration.class))
            .withPropertyValues("peekaboot.enabled=true")
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

    /** Every toolbar bean is {@code @ConditionalOnMissingBean}, so an application can supply its own. */
    @Test
    void userSuppliedSameNamedBeansReplaceTheDefaults() {
        contextRunner
                .withPropertyValues("peekaboot.dev-toolbar=true")
                .withUserConfiguration(MockTracingConfig.class, UserSameNamedBeansConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("toolbarDataProvider"))
                            .isSameAs(UserSameNamedBeansConfig.TOOLBAR_DATA_PROVIDER);
                    assertThat(context.getBean("devToolbarFilter"))
                            .isSameAs(UserSameNamedBeansConfig.FILTER_REGISTRATION);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class UserSameNamedBeansConfig {

        static final ToolbarDataProvider TOOLBAR_DATA_PROVIDER = new ToolbarDataProvider();
        static final FilterRegistrationBean<DevToolbarFilter> FILTER_REGISTRATION = new FilterRegistrationBean<>();

        @Bean
        ToolbarDataProvider toolbarDataProvider() {
            return TOOLBAR_DATA_PROVIDER;
        }

        @Bean
        FilterRegistrationBean<DevToolbarFilter> devToolbarFilter() {
            return FILTER_REGISTRATION;
        }
    }

    /** The springdoc property decides where idle mode applies; it has to reach the filter. */
    @Test
    void theCustomisedSwaggerUiPathReachesTheToolbarFilter() {
        contextRunner
                .withPropertyValues("peekaboot.dev-toolbar=true", "springdoc.swagger-ui.path=/admin/docs.html")
                .withUserConfiguration(MockTracingConfig.class)
                .run(context -> {
                    DevToolbarFilter filter =
                            (DevToolbarFilter) context.getBean("devToolbarFilter", FilterRegistrationBean.class)
                                    .getFilter();
                    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/swagger-ui/index.html");
                    request.setServletPath("/admin/swagger-ui/index.html");
                    MockHttpServletResponse response = new MockHttpServletResponse();
                    filter.doFilter(request, response, (req, res) -> {
                        res.setContentType("text/html");
                        res.getWriter().write("<html><body></body></html>");
                    });

                    assertThat(response.getContentAsString()).contains("\"idle\":true");
                });
    }

    @Test
    void shouldNotCreateBeansWhenDevToolbarDisabled() {
        contextRunner.withPropertyValues("peekaboot.dev-toolbar=false").run(context -> {
            assertThat(context).doesNotHaveBean(ToolbarDataProvider.class);
            assertThat(context).doesNotHaveBean("devToolbarFilter");
        });
    }

    @Test
    void shouldNotCreateBeansWhenDevToolbarPropertyMissing() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(ToolbarDataProvider.class);
            assertThat(context).doesNotHaveBean("devToolbarFilter");
        });
    }

    /**
     * With tracing off there is no store: the toolbar still shows the trace id it reads from
     * the Tracer, but the request and log capture would publish events for nobody.
     */
    @Test
    void capturesNothingWithoutATraceStore() {
        contextRunner
                .withPropertyValues("peekaboot.dev-toolbar=true")
                .withUserConfiguration(TracerOnlyConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ToolbarDataProvider.class);
                    assertThat(context).hasBean("devToolbarFilter");
                    assertThat(context).doesNotHaveBean("requestCaptureFilter");
                    assertThat(context).doesNotHaveBean(DevToolbarAutoConfiguration.LogbackAppenderRegistrar.class);
                });
    }

    @Test
    void shouldNotCreateFilterBeansWhenTracerBeanMissing() {
        // TraceStore is on the classpath and present as a bean, but no Tracer
        // bean exists: devToolbarFilter/requestCaptureFilter's
        // @ConditionalOnBean(Tracer.class) must keep them unregistered, while
        // toolbarDataProvider (no such condition) still registers.
        contextRunner
                .withPropertyValues("peekaboot.dev-toolbar=true")
                .withUserConfiguration(TraceStoreOnlyConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ToolbarDataProvider.class);
                    assertThat(context).doesNotHaveBean("devToolbarFilter");
                    assertThat(context).doesNotHaveBean("requestCaptureFilter");
                });
    }

    @Test
    void shouldNotCreateBeansWhenGlobalEnabledPropertyMissing() {
        // matchIfMissing = false: without the environment post-processor's detected
        // default the safe fallback is off, even with the toolbar flag set
        new WebApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(DevToolbarAutoConfiguration.class, PeekabootAutoConfiguration.class))
                .withUserConfiguration(MockActuatorConfig.class, MockTracingConfig.class)
                .withPropertyValues("peekaboot.dev-toolbar=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
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

    /**
     * Spring Boot re-initialises Logback for every application that starts in the JVM, which
     * detaches and stops every appender registered in code. Appenders declared in
     * {@code logback.xml} come back with the configuration that follows; peekaboot's does not,
     * leaving an application context that is still serving requests recording traces with no
     * logs against them.
     *
     * <p>Asserts on a captured event rather than on attachment, because an appender that is
     * re-attached without being restarted is still silently dropping everything.
     */
    @Test
    void logCaptureSurvivesLogbackReinitialisation() {
        contextRunner
                .withPropertyValues("peekaboot.dev-toolbar=true")
                .withUserConfiguration(MockTracingConfig.class, CapturedLogRecorder.class)
                .run(context -> {
                    CapturedLogRecorder recorder = context.getBean(CapturedLogRecorder.class);
                    logWithTraceId("before reinitialisation");
                    assertThat(recorder.messages())
                            .as("capture must work beforehand, or the assertion below would "
                                    + "pass on an appender that never captured anything")
                            .contains("before reinitialisation");

                    reinitialiseLogback();
                    logWithTraceId("after reinitialisation");

                    assertThat(recorder.messages())
                            .as("log capture must survive the Logback re-initialisation that "
                                    + "every newly started application triggers")
                            .contains("after reinitialisation");
                });
    }

    /**
     * Reproduces what Spring Boot's {@code LogbackLoggingSystem.stopAndReset} does - stopping
     * the logger context (which drops every listener, reset-resistant ones included) and then
     * resetting it - restores Logback's default configuration through {@code autoConfig()}
     * (this module has no logback-test.xml), and then fires the event that carries the repair.
     */
    private void reinitialiseLogback() throws Exception {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        loggerContext.stop();
        loggerContext.reset();
        new ContextInitializer(loggerContext).autoConfig();
        loggerContext.start();

        new LogbackCaptureReinstaller()
                .onApplicationEvent(new ApplicationEnvironmentPreparedEvent(
                        new DefaultBootstrapContext(),
                        new SpringApplication(),
                        new String[0],
                        new StandardEnvironment()));
    }

    /**
     * Only events carrying a traceId in the MDC are captured. The event has to travel up to
     * the root logger, where the capture appender hangs, so root's other appenders - the
     * console - are unhooked for the one statement rather than additivity switched off.
     */
    private void logWithTraceId(String message) {
        Logger root = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME);
        List<Appender<ILoggingEvent>> unhooked = new ArrayList<>();
        Iterator<Appender<ILoggingEvent>> it = root.iteratorForAppenders();
        while (it.hasNext()) {
            Appender<ILoggingEvent> appender = it.next();
            if (!(appender instanceof PeekabootLogbackAppender)) {
                unhooked.add(appender);
            }
        }
        unhooked.forEach(root::detachAppender);
        MDC.put("traceId", "0123456789abcdef0123456789abcdef");
        try {
            LoggerFactory.getLogger(DevToolbarAutoConfigurationTest.class).error(message);
        } finally {
            MDC.remove("traceId");
            unhooked.forEach(root::addAppender);
        }
    }

    /**
     * The repair only reaches a running application context if Spring Boot instantiates the
     * listener for every application, after the re-initialisation it is undoing.
     */
    @Test
    void logbackCaptureReinstallerRunsForEveryApplicationAfterLoggingIsInitialised() {
        assertThat(SpringFactoriesLoader.loadFactoryNames(
                        ApplicationListener.class, getClass().getClassLoader()))
                .as("registered in spring.factories, so every application repairs the capture")
                .contains(LogbackCaptureReinstaller.class.getName());

        assertThat(new LogbackCaptureReinstaller().getOrder())
                .as("must run after LoggingApplicationListener, which performs the reset")
                .isGreaterThan(LoggingApplicationListener.DEFAULT_ORDER);
    }

    @Configuration(proxyBeanMethods = false)
    static class CapturedLogRecorder {

        private final List<String> messages = new CopyOnWriteArrayList<>();

        @EventListener
        void onLogCaptured(LogCapturedEvent event) {
            messages.add(event.message());
        }

        List<String> messages() {
            return messages;
        }
    }

    /**
     * The toolbar can be on while PeekabootAutoConfiguration - and with it the shared
     * MaskingEngine bean - is backed off (no actuator endpoint classes on the host
     * classpath); the capture filter then falls back to a private engine instead of
     * failing the context.
     */
    @Test
    void theCaptureFilterWorksWithoutTheDashboardsMaskingEngineBean() {
        new WebApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(DevToolbarAutoConfiguration.class, PeekabootPathsAutoConfiguration.class))
                .withUserConfiguration(MinimalPropertiesConfig.class, MockTracingConfig.class)
                .withPropertyValues("peekaboot.enabled=true", "peekaboot.dev-toolbar=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("requestCaptureFilter");
                });
    }

    @Test
    void shouldNotCreateLogbackRegistrarWhenLogbackMissing() {
        contextRunner
                .withPropertyValues("peekaboot.dev-toolbar=true")
                .withUserConfiguration(MockTracingConfig.class)
                .withClassLoader(new FilteredClassLoader(LoggerContext.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(DevToolbarAutoConfiguration.LogbackAppenderRegistrar.class);
                });
    }

    private int peekabootAppenderCount() {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        int count = 0;
        Iterator<Appender<ILoggingEvent>> it = root.iteratorForAppenders();
        while (it.hasNext()) {
            if (it.next() instanceof PeekabootLogbackAppender) {
                count++;
            }
        }
        return count;
    }

    @Configuration
    @EnableConfigurationProperties(PeekabootTracingProperties.class)
    static class MockTracingConfig {
        @Bean
        TraceStore traceStore() {
            return new InMemoryTraceStore(100, 50);
        }

        @Bean
        TraceStoreEventListener traceStoreEventListener(TraceStore traceStore) {
            return new TraceStoreEventListener(traceStore);
        }

        @Bean
        Tracer tracer() {
            return mock(Tracer.class);
        }
    }

    @Configuration
    @EnableConfigurationProperties(PeekabootProperties.class)
    static class MinimalPropertiesConfig {}

    @Configuration
    static class TraceStoreOnlyConfig {
        @Bean
        TraceStore traceStore() {
            return new InMemoryTraceStore(100, 50);
        }
    }

    @Configuration
    static class TracerOnlyConfig {
        @Bean
        Tracer tracer() {
            return mock(Tracer.class);
        }
    }
}
