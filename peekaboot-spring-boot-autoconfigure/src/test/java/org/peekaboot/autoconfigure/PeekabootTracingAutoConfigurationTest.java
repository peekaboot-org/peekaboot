package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.tracing.bridge.otel.OtelSpanExporter;
import org.peekaboot.backend.tracing.interceptor.TracingHandlerInterceptor;
import org.peekaboot.backend.tracing.store.SpanData;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.peekaboot.backend.tracing.store.TraceStoreEventListener;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.handler.MappedInterceptor;

class PeekabootTracingAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PeekabootTracingAutoConfiguration.class,
                    OtelTracingAutoConfiguration.class,
                    PeekabootPathsAutoConfiguration.class))
            .withPropertyValues("peekaboot.enabled=true");

    @Test
    void shouldCreateCoreBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(TraceStore.class);
            assertThat(context).hasSingleBean(TraceStoreEventListener.class);
        });
    }

    @Test
    void shouldCreateOtelSpanExporterWhenOtelOnClasspath() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(OtelSpanExporter.class);
        });
    }

    @Test
    void shouldNotCreateBeansWhenDisabled() {
        contextRunner.withPropertyValues("peekaboot.tracing.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(TraceStore.class);
            assertThat(context).doesNotHaveBean(OtelSpanExporter.class);
        });
    }

    @Test
    void shouldNotCreateBeansWhenPeekabootGloballyDisabled() {
        contextRunner.withPropertyValues("peekaboot.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(TraceStore.class);
            assertThat(context).doesNotHaveBean(OtelSpanExporter.class);
        });
    }

    /** Everything that reads the store is servlet-only; a WebFlux or non-web app would fill it for nobody. */
    @Test
    void shouldNotCreateBeansWhenNotAServletApplication() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        PeekabootTracingAutoConfiguration.class,
                        OtelTracingAutoConfiguration.class,
                        PeekabootPathsAutoConfiguration.class))
                .withPropertyValues("peekaboot.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TraceStore.class);
                    assertThat(context).doesNotHaveBean(OtelSpanExporter.class);
                });
    }

    @Test
    void shouldNotCreateBeansWhenGlobalEnabledPropertyMissing() {
        // matchIfMissing = false: without the environment post-processor's detected
        // default the safe fallback is off
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        PeekabootTracingAutoConfiguration.class,
                        OtelTracingAutoConfiguration.class,
                        PeekabootPathsAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(TraceStore.class);
                    assertThat(context).doesNotHaveBean(OtelSpanExporter.class);
                });
    }

    @Test
    void bucketPropertiesReachTheStore() {
        contextRunner
                .withPropertyValues(
                        "peekaboot.tracing.max-error-traces=1", "peekaboot.tracing.slow-trace-threshold-ms=1")
                .run(context -> {
                    TraceStore store = context.getBean(TraceStore.class);
                    // slow threshold 1ms: a 5ms span classifies as slow
                    Instant start = Instant.parse("2026-01-01T00:00:00Z");
                    store.addSpan(new SpanData(
                            "t1",
                            "s1",
                            null,
                            "op",
                            null,
                            start,
                            start.plusMillis(5),
                            Duration.ofMillis(5),
                            Map.of(),
                            List.of(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            List.of(),
                            1L));
                    assertThat(store.getTraceCount(TraceBucket.SLOW)).isEqualTo(1);
                });
    }

    // --- TracingInterceptorAutoConfiguration ---

    private final WebApplicationContextRunner webContextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingInterceptorAutoConfiguration.class, PeekabootPathsAutoConfiguration.class))
            .withPropertyValues("peekaboot.enabled=true");

    @Test
    void shouldRegisterInterceptorWhenObservationRegistryBeanPresentInWebApp() {
        webContextRunner.withUserConfiguration(ObservationRegistryConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TracingHandlerInterceptor.class);
            assertThat(context).hasSingleBean(WebMvcConfigurer.class);
        });
    }

    @Test
    void shouldNotRegisterInterceptorWhenNoObservationRegistryBean() {
        webContextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(TracingHandlerInterceptor.class);
        });
    }

    @Test
    void shouldNotRegisterInterceptorWhenNotAWebApplication() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        TracingInterceptorAutoConfiguration.class, PeekabootPathsAutoConfiguration.class))
                .withPropertyValues("peekaboot.enabled=true")
                .withUserConfiguration(ObservationRegistryConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TracingHandlerInterceptor.class);
                });
    }

    @Test
    void shouldNotRegisterInterceptorWhenGlobalEnabledPropertyMissing() {
        // matchIfMissing = false: without the environment post-processor's detected
        // default the safe fallback is off
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        TracingInterceptorAutoConfiguration.class, PeekabootPathsAutoConfiguration.class))
                .withUserConfiguration(ObservationRegistryConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TracingHandlerInterceptor.class);
                });
    }

    @Test
    void shouldNotRegisterInterceptorWhenPeekabootDisabled() {
        webContextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .withPropertyValues("peekaboot.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TracingHandlerInterceptor.class);
                });
    }

    /** Handler and view observations are tracing; with tracing off there is no store to land in. */
    @Test
    void shouldNotRegisterInterceptorWhenTracingDisabled() {
        webContextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .withPropertyValues("peekaboot.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TracingHandlerInterceptor.class);
                });
    }

    @Test
    void shouldRegisterInterceptorWithExpectedPathPatterns() {
        webContextRunner.withUserConfiguration(ObservationRegistryConfig.class).run(context -> {
            WebMvcConfigurer configurer = context.getBean(WebMvcConfigurer.class);
            RecordingInterceptorRegistry registry = new RecordingInterceptorRegistry();
            configurer.addInterceptors(registry);

            List<Object> registered = registry.registered();
            assertThat(registered).hasSize(1);
            MappedInterceptor mapped = (MappedInterceptor) registered.getFirst();

            assertThat(mapped.getIncludePathPatterns()).containsExactly("/**");
            // context-relative MVC patterns, so a server.servlet.context-path changes nothing here
            assertThat(mapped.getExcludePathPatterns())
                    .containsExactlyInAnyOrder(
                            "/peekaboot/**", "/actuator/**", "/static/**", "/webjars/**", "/error/**", "/error");
        });
    }

    /** The actuator exclusion follows a relocated management base path, through the shared PeekabootPaths bean. */
    @Test
    void theManagementBasePathReachesTheInterceptorExclusions() {
        webContextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .withPropertyValues("management.endpoints.web.base-path=/manage")
                .run(context -> {
                    WebMvcConfigurer configurer = context.getBean(WebMvcConfigurer.class);
                    RecordingInterceptorRegistry registry = new RecordingInterceptorRegistry();
                    configurer.addInterceptors(registry);

                    MappedInterceptor mapped =
                            (MappedInterceptor) registry.registered().getFirst();
                    assertThat(mapped.getExcludePathPatterns())
                            .contains("/manage/**")
                            .doesNotContain("/actuator/**");
                });
    }

    /**
     * {@code InterceptorRegistry.getInterceptors()} is protected with no public
     * accessor; a test-local subclass reaches it with compile-time safety
     * instead of reflection.
     */
    private static final class RecordingInterceptorRegistry extends InterceptorRegistry {
        List<Object> registered() {
            return getInterceptors();
        }
    }

    @Configuration
    static class ObservationRegistryConfig {
        @Bean
        ObservationRegistry observationRegistry() {
            return ObservationRegistry.create();
        }
    }
}
