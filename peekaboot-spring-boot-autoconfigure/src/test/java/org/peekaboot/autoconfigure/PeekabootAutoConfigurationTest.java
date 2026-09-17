package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Span;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.config.PeekabootProperties;
import org.peekaboot.backend.controller.PeekabootController;
import org.peekaboot.backend.domain.features.Features;
import org.peekaboot.backend.domain.trace.TraceLog;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.service.MetricsService;
import org.peekaboot.backend.service.TraceInsightsService;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.store.SpanData;
import org.peekaboot.backend.tracing.store.TraceStore;
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
     * PeekabootAutoConfiguration resolves {@code peekaboot.stack-trace.fold} for the trace
     * store's own folding the same way {@code ErrorPageAutoConfiguration} does for the error
     * page: off must empty the exclusion list rather than leave it unread, so no frame is ever
     * hidden regardless of what {@code peekaboot.stack-trace.exclude} names. Drives a real
     * captured log through the actual bean rather than asserting on construction alone, since a
     * bean existing proves nothing about what reached its constructor.
     */
    @Test
    void honoursTheFoldSwitchForCapturedLogs() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        PeekabootAutoConfiguration.class, PeekabootTracingAutoConfiguration.class))
                .withPropertyValues(
                        "peekaboot.enabled=true",
                        "peekaboot.stack-trace.fold=false",
                        "peekaboot.stack-trace.exclude=com.acme.vendor")
                .run(context -> {
                    TraceStore traceStore = context.getBean(TraceStore.class);
                    traceStore.addSpan(new SpanData(
                            "t1",
                            "s1",
                            null,
                            "GET /x",
                            Span.Kind.SERVER,
                            Instant.EPOCH,
                            Instant.EPOCH.plusMillis(100),
                            Duration.ofMillis(100),
                            Map.of(),
                            List.of(),
                            null,
                            null,
                            null,
                            1L));
                    traceStore.addLog(
                            new LogCapturedEvent(
                                    "t1",
                                    "s1",
                                    Instant.EPOCH,
                                    "ERROR",
                                    "TestLogger",
                                    "boom",
                                    "main",
                                    "java.lang.IllegalStateException: boom\n\tat com.acme.vendor.Client.call(Client.java:42)\n"));

                    TraceInsightsService service = context.getBean(TraceInsightsService.class);
                    TraceLog log =
                            service.getTraceInsights("t1").orElseThrow().logs().getFirst();

                    assertThat(log.hiddenFrames())
                            .as("fold=false must empty the exclusion list, not just leave it unread")
                            .isEmpty();
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
