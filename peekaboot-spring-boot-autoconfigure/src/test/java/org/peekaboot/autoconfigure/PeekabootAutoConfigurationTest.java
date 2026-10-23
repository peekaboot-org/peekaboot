package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.peekaboot.backend.config.PeekabootProperties;
import org.peekaboot.backend.controller.PeekabootController;
import org.peekaboot.backend.domain.features.Features;
import org.peekaboot.backend.domain.trace.SqlStatement;
import org.peekaboot.backend.domain.trace.TraceLog;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.service.MetricsService;
import org.peekaboot.backend.service.TraceInsightsService;
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
                                    .getTraceInsights(TraceFixtures.TRACE_ID)
                                    .orElseThrow()
                                    .rootSpan()
                                    .issues())
                            .isEmpty();
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
    void doesNotRegisterBeansWhenPeekabootDisabled() {
        contextRunner.withPropertyValues("peekaboot.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(PeekabootController.class);
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
                            .getTraceInsights(TraceFixtures.TRACE_ID)
                            .orElseThrow()
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
                .getTraceInsights(TraceFixtures.TRACE_ID)
                .orElseThrow()
                .rootSpan()
                .children()
                .getFirst()
                .statement();
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
}
