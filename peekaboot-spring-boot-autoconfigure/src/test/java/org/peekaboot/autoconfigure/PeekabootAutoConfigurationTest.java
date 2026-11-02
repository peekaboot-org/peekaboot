package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.peekaboot.backend.config.PeekabootProperties;
import org.peekaboot.backend.controller.LogController;
import org.peekaboot.backend.controller.PeekabootController;
import org.peekaboot.backend.controller.TraceController;
import org.peekaboot.backend.domain.features.Features;
import org.peekaboot.backend.domain.trace.IssueType;
import org.peekaboot.backend.domain.trace.SqlStatement;
import org.peekaboot.backend.domain.trace.TraceIssue;
import org.peekaboot.backend.domain.trace.TraceLog;
import org.peekaboot.backend.insights.InsightsService;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.service.LogSearchService;
import org.peekaboot.backend.service.MetricsService;
import org.peekaboot.backend.service.TraceInsightsService;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

class PeekabootAutoConfigurationTest {

    // PeekabootAutoConfiguration is @ConditionalOnWebApplication(SERVLET), so most of
    // this class exercises it through a servlet web application context; only
    // doesNotRegisterBeansOnNonServletApplication uses the plain, non-servlet runner.
    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PeekabootAutoConfiguration.class));

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

    /** Like lifecycle's, the switch that turned a feature off stays on the Config tab, which lists the bound beans. */
    @Test
    void bindsTheTracingAndInsightsSwitchesWhileTheirFeaturesAreOff() {
        contextRunner
                .withConfiguration(
                        AutoConfigurations.of(PeekabootTracingAutoConfiguration.class, InsightsAutoConfiguration.class))
                .withPropertyValues(
                        "peekaboot.enabled=true", "peekaboot.tracing.enabled=false", "peekaboot.insights.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(TraceStore.class);
                    assertThat(context).doesNotHaveBean(InsightsService.class);
                    assertThat(context.getBean(PeekabootTracingProperties.class).isEnabled())
                            .isFalse();
                    assertThat(context.getBean(InsightsProperties.class).isEnabled())
                            .isFalse();
                });
    }

    /** The dashboard hides the Lifecycle tab from this flag, as it hides Insights. */
    @ParameterizedTest
    @CsvSource({"true, true", "false, false"})
    void publishesWhetherTheLifecycleHistoryIsServed(boolean lifecycleEnabled, boolean published) {
        contextRunner
                .withConfiguration(AutoConfigurations.of(
                        PeekabootStorageAutoConfiguration.class, PeekabootLifecycleAutoConfiguration.class))
                .withPropertyValues("peekaboot.enabled=true", "peekaboot.lifecycle.enabled=" + lifecycleEnabled)
                .run(context -> assertThat(context.getBean(PeekabootController.class)
                                .getFeatures()
                                .lifecycle())
                        .isEqualTo(published));
    }

    @Test
    void publishesTheUnmaskingSwitch() {
        contextRunner
                .withPropertyValues("peekaboot.enabled=true", "peekaboot.unmasking.enabled=true")
                .run(context -> assertThat(context.getBean(PeekabootController.class)
                                .getFeatures()
                                .unmaskingEnabled())
                        .isTrue());
    }

    /** Bound without the trace store too: the issue detector and /api/features need them while tracing is off. */
    @Test
    void publishesTheIssueThresholdsBoundUnderTracing() {
        contextRunner
                .withPropertyValues(
                        "peekaboot.enabled=true",
                        "peekaboot.tracing.slow-span-threshold-ms=250",
                        "peekaboot.tracing.very-slow-span-threshold-ms=900",
                        "peekaboot.tracing.slow-query-threshold-ms=75")
                .run(context -> {
                    Features features =
                            context.getBean(PeekabootController.class).getFeatures();
                    assertThat(features.slowSpanThresholdMs()).isEqualTo(250L);
                    assertThat(features.verySlowSpanThresholdMs()).isEqualTo(900L);
                    assertThat(features.slowQueryThresholdMs()).isEqualTo(75L);
                });
    }

    /** A 200 ms span is SLOW at the default 100 ms; the configured threshold has to reach the detector to clear it. */
    @Test
    void raisesIssuesAtTheSpanThresholdBoundUnderTracing() {
        tracingRunner()
                .withPropertyValues("peekaboot.tracing.slow-span-threshold-ms=250")
                .run(context -> {
                    context.getBean(TraceStore.class).addSpan(TraceFixtures.rootSpanLasting(200));

                    assertThat(context.getBean(TraceInsightsService.class)
                                    .getTrace(TraceFixtures.TRACE_ID)
                                    .orElseThrow()
                                    .data()
                                    .rootSpan()
                                    .issues())
                            .isEmpty();
                });
    }

    /** The lowest threshold that can mean a repeat; the configured value has to reach the detector. */
    @Test
    void raisesRepeatedQueryAtTheThresholdBoundUnderTracing() {
        tracingRunner()
                .withPropertyValues("peekaboot.tracing.repeated-query-threshold=2")
                .run(context -> {
                    TraceStore store = context.getBean(TraceStore.class);
                    TraceFixtures.addRootSpan(store);
                    store.addSpan(TraceFixtures.query("q1", "select * from t where id = 1"));
                    store.addSpan(TraceFixtures.query("q2", "select * from t where id = 2"));

                    assertThat(context.getBean(TraceInsightsService.class)
                                    .getTrace(TraceFixtures.TRACE_ID)
                                    .orElseThrow()
                                    .data()
                                    .rootSpan()
                                    .issues())
                            .extracting(TraceIssue::type)
                            .containsExactly(IssueType.REPEATED_QUERY);
                });
    }

    /** The bound cap reaches the store's ring: the oldest line is the one that goes. */
    @Test
    void keepsTheUntracedLogCapBoundUnderTracing() {
        tracingRunner()
                .withPropertyValues("peekaboot.tracing.max-untraced-logs=2")
                .run(context -> {
                    TraceStore store = context.getBean(TraceStore.class);
                    store.addLog(TraceFixtures.untracedLog("first"));
                    store.addLog(TraceFixtures.untracedLog("second"));
                    store.addLog(TraceFixtures.untracedLog("third"));

                    assertThat(store.untracedLogs())
                            .extracting(LogCapturedEvent::message)
                            .containsExactly("second", "third");
                });
    }

    @Test
    void registersBeansWhenEnabledAndEndpointClassesPresent() {
        contextRunner.withPropertyValues("peekaboot.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PeekabootProperties.class);
            // Proves the explicit registrations actually wire up the whole
            // controller/service/mapper graph, not just the properties beans.
            assertThat(context).hasSingleBean(PeekabootController.class);
            assertThat(context).hasSingleBean(TraceController.class);
            assertThat(context).hasSingleBean(LogController.class);
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

    /**
     * Component scanning names a host app's own {@code TraceController} {@code traceController}. A
     * Peekaboot bean of another type under that name would stop the context from starting.
     */
    @Test
    void startsBesideAHostBeanNamedTraceController() {
        contextRunner
                .withPropertyValues("peekaboot.enabled=true")
                .withUserConfiguration(HostTraceControllerConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("traceController")).isInstanceOf(HostTraceController.class);
                    assertThat(context).hasSingleBean(TraceController.class);
                });
    }

    /**
     * Component scanning names a host app's own {@code LogController} {@code logController} and its
     * {@code LogSearchService} {@code logSearchService}, so Peekaboot's beans take other names.
     */
    @Test
    void startsBesideHostBeansNamedLogControllerAndLogSearchService() {
        contextRunner
                .withPropertyValues("peekaboot.enabled=true")
                .withUserConfiguration(HostLogSearchConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("logController")).isInstanceOf(HostLogController.class);
                    assertThat(context.getBean("logSearchService")).isInstanceOf(HostLogSearchService.class);
                    assertThat(context).hasSingleBean(LogController.class);
                    assertThat(context).hasSingleBean(LogSearchService.class);
                });
    }

    @Test
    void doesNotRegisterBeansWhenPeekabootDisabled() {
        contextRunner.withPropertyValues("peekaboot.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(PeekabootController.class);
            assertThat(context).doesNotHaveBean(TraceController.class);
            assertThat(context).doesNotHaveBean(LogController.class);
            assertThat(context).doesNotHaveBean(PeekabootProperties.class);
        });
    }

    @Test
    void doesNotRegisterBeansOnNonServletApplication() {
        // peekaboot.enabled=true is the default in local development; on a reactive or
        // non-web application PeekabootAutoConfiguration must stay inactive rather than
        // partially activating a servlet-only component (PeekabootWebConfig).
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PeekabootAutoConfiguration.class))
                .withPropertyValues("peekaboot.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PeekabootController.class);
                    assertThat(context).doesNotHaveBean(TraceController.class);
                    assertThat(context).doesNotHaveBean(LogController.class);
                    assertThat(context).doesNotHaveBean(PeekabootProperties.class);
                });
    }

    @Test
    void doesNotRegisterBeansWhenSpringWebmvcAbsentEntirely() {
        // doesNotRegisterBeansOnNonServletApplication only proves the guard works when
        // spring-webmvc is present but simply unused by a plain, non-web context — this
        // module's own test classpath always carries spring-webmvc. A genuinely reactive
        // application doesn't have spring-webmvc on the classpath at all, so hide
        // WebMvcConfigurer (and the registry types PeekabootWebConfig references) on a
        // reactive web context to simulate that absence for real: the context must still
        // start cleanly and register no Peekaboot beans, rather than failing to load
        // PeekabootWebConfig with a NoClassDefFoundError.
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PeekabootAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader(
                        WebMvcConfigurer.class, ResourceHandlerRegistry.class, ViewControllerRegistry.class))
                .withPropertyValues("peekaboot.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PeekabootController.class);
                    assertThat(context).doesNotHaveBean(TraceController.class);
                    assertThat(context).doesNotHaveBean(LogController.class);
                    assertThat(context).doesNotHaveBean(PeekabootProperties.class);
                });
    }

    @Test
    void doesNotRegisterBeansWhenHealthEndpointClassMissing() {
        contextRunner
                .withPropertyValues("peekaboot.enabled=true")
                .withClassLoader(new FilteredClassLoader(HealthEndpoint.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PeekabootController.class);
                    assertThat(context).doesNotHaveBean(TraceController.class);
                    assertThat(context).doesNotHaveBean(LogController.class);
                });
    }

    /**
     * StackTraceRulesConfiguration resolves {@code peekaboot.stack-trace.fold} once, for the trace
     * store's folding and the error page alike: off must empty the exclusion list rather than
     * leave it unread, so no frame is ever hidden regardless of what
     * {@code peekaboot.stack-trace.exclude} names. With no fold setting at all the context runner
     * runs no post-processor, so only the bound default decides. Drives a real captured log
     * through the actual bean rather than asserting on construction alone, since a bean existing
     * proves nothing about what reached its constructor.
     */
    @ParameterizedTest
    @CsvSource({"peekaboot.stack-trace.fold=false, false", "peekaboot.stack-trace.fold=true, true", "'', true"})
    void foldsCapturedLogsAsTheFoldSwitchSays(String foldSetting, boolean folded) {
        tracingRunner()
                .withPropertyValues("peekaboot.stack-trace.exclude=com.acme.vendor", foldSetting)
                .run(context -> {
                    TraceStore traceStore = context.getBean(TraceStore.class);
                    TraceFixtures.addRootSpan(traceStore);
                    traceStore.addLog(TraceFixtures.vendorFrameLog());

                    TraceLog log = context.getBean(TraceInsightsService.class)
                            .getTrace(TraceFixtures.TRACE_ID)
                            .orElseThrow()
                            .data()
                            .logs()
                            .getFirst();

                    assertThat(log.hiddenFrames().isEmpty()).isEqualTo(!folded);
                });
    }

    /** Drives a captured query through the real TraceInsightsService: a bean existing proves nothing about what reached the mappers. */
    @Test
    void formatsCapturedQueriesWhenHibernateIsPresent() {
        tracingRunner()
                .run(context ->
                        assertThat(capturedStatement(context).formatted()).isEqualTo("    select\n        1"));
    }

    @Test
    void servesQueriesUnformattedWithoutHibernate() {
        tracingRunner()
                .withClassLoader(new FilteredClassLoader("org.hibernate"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(capturedStatement(context)).isEqualTo(new SqlStatement("select 1", null, List.of(), 0));
                });
    }

    /**
     * {@code features.tracing} only says the trace store exists; it says nothing about
     * whether anything can fill it. The store is wired by {@code PeekabootTracingAutoConfiguration}
     * alone, with no class-path guard, while {@code OtelSpanExporter} - the only span source
     * the store has - is wired by {@code OtelTracingAutoConfiguration}, guarded by the
     * OpenTelemetry SDK's presence. These two tests pin that {@code tracingSpansPossible}
     * tracks the guarded bean, not the unguarded one.
     */
    @Nested
    class TracingSpansPossible {

        private final WebApplicationContextRunner tracingContextRunner = new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        PeekabootAutoConfiguration.class,
                        PeekabootTracingAutoConfiguration.class,
                        OtelTracingAutoConfiguration.class,
                        DispatcherServletAutoConfiguration.class,
                        PeekabootPathsAutoConfiguration.class))
                .withPropertyValues("peekaboot.enabled=true");

        @Test
        void isTrueWhenTheOpenTelemetrySdkIsOnTheClassPath() {
            tracingContextRunner.run(context -> {
                Features features = context.getBean(PeekabootController.class).getFeatures();
                assertThat(features.tracing()).isTrue();
                assertThat(features.tracingSpansPossible()).isTrue();
            });
        }

        @Test
        void isFalseWithoutTheOpenTelemetrySdkEvenThoughTheStoreStillExists() {
            tracingContextRunner
                    .withClassLoader(new FilteredClassLoader(SpanExporter.class))
                    .run(context -> {
                        Features features =
                                context.getBean(PeekabootController.class).getFeatures();
                        assertThat(features.tracing()).isTrue();
                        assertThat(features.tracingSpansPossible()).isFalse();
                    });
        }
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

    @Configuration(proxyBeanMethods = false)
    static class HostTraceControllerConfig {

        @Bean
        HostTraceController traceController() {
            return new HostTraceController();
        }
    }

    static class HostTraceController {}

    @Configuration(proxyBeanMethods = false)
    static class HostLogSearchConfig {

        @Bean
        HostLogController logController() {
            return new HostLogController();
        }

        @Bean
        HostLogSearchService logSearchService() {
            return new HostLogSearchService();
        }
    }

    static class HostLogController {}

    static class HostLogSearchService {}

    private static WebApplicationContextRunner tracingRunner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        PeekabootAutoConfiguration.class, PeekabootTracingAutoConfiguration.class))
                .withPropertyValues("peekaboot.enabled=true");
    }

    private static SqlStatement capturedStatement(AssertableWebApplicationContext context) {
        TraceStore traceStore = context.getBean(TraceStore.class);
        TraceFixtures.addRootSpan(traceStore);
        traceStore.addSpan(TraceFixtures.query("select 1"));
        return context.getBean(TraceInsightsService.class)
                .getTrace(TraceFixtures.TRACE_ID)
                .orElseThrow()
                .data()
                .rootSpan()
                .children()
                .getFirst()
                .statement();
    }
}
