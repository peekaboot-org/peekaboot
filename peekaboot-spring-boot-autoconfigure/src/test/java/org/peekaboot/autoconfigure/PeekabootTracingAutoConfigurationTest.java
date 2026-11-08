package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.tracing.bridge.otel.OtelSpanExporter;
import org.peekaboot.backend.tracing.digest.ErrorDigest;
import org.peekaboot.backend.tracing.digest.ErrorDigestRecorder;
import org.peekaboot.backend.tracing.event.SpanDataEvent;
import org.peekaboot.backend.tracing.interceptor.TracingHandlerInterceptor;
import org.peekaboot.backend.tracing.journal.TraceJournal;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.peekaboot.backend.tracing.store.TraceStoreEventListener;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.handler.MappedInterceptor;

class PeekabootTracingAutoConfigurationTest {

    /** A batch that never exports on its own schedule within a test. */
    private static final Duration NO_SCHEDULED_EXPORT = Duration.ofHours(1);

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PeekabootTracingAutoConfiguration.class,
                    OtelTracingAutoConfiguration.class,
                    DispatcherServletAutoConfiguration.class,
                    PeekabootPathsAutoConfiguration.class))
            .withPropertyValues("peekaboot.enabled=true");

    /** The interceptor auto-configuration is covered here as well; it shares the paths bean. */
    private final WebApplicationContextRunner webContextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingInterceptorAutoConfiguration.class,
                    DispatcherServletAutoConfiguration.class,
                    PeekabootPathsAutoConfiguration.class))
            .withPropertyValues("peekaboot.enabled=true");

    private final WebApplicationContextRunner storageRunner =
            contextRunner.withConfiguration(AutoConfigurations.of(PeekabootStorageAutoConfiguration.class));

    @TempDir
    Path storageDir;

    @Test
    void createsCoreBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(TraceStore.class);
            assertThat(context).hasSingleBean(TraceStoreEventListener.class);
            assertThat(context).hasSingleBean(ErrorDigest.class).hasBean("peekabootErrorDigest");
            assertThat(context).hasSingleBean(ErrorDigestRecorder.class).hasBean("peekabootErrorDigestRecorder");
        });
    }

    /** Without a journal the listener hands a live input to the store and then to the error digest. */
    @Test
    void aFailedRequestReachesTheErrorDigest() {
        contextRunner.run(context -> {
            context.publishEvent(new SpanDataEvent(TraceFixtures.failedRootSpan()));

            assertThat(context.getBean(ErrorDigest.class).entries())
                    .singleElement()
                    .satisfies(entry -> {
                        assertThat(entry.lastTraceId()).isEqualTo(TraceFixtures.TRACE_ID);
                        assertThat(entry.route()).isEqualTo("/x");
                    });
        });
    }

    /** With the journal, a live failure passes its restore gate to the digest as well as to the store. */
    @Test
    void theJournalHandsALiveFailureToTheErrorDigest() {
        storageRunner
                .withPropertyValues("peekaboot.storage.enabled=true", "peekaboot.storage.dir=" + storageDir)
                .run(context -> {
                    context.publishEvent(new SpanDataEvent(TraceFixtures.failedRootSpan()));

                    ErrorDigest digest = context.getBean(ErrorDigest.class);
                    await().atMost(Duration.ofSeconds(5))
                            .until(() -> !digest.entries().isEmpty());
                    assertThat(digest.entries().getFirst().lastTraceId()).isEqualTo(TraceFixtures.TRACE_ID);
                });
    }

    @Test
    void createsOtelSpanExporterWhenOtelOnClasspath() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(OtelSpanExporter.class);
        });
    }

    @Test
    void doesNotCreateBeansWhenDisabled() {
        contextRunner.withPropertyValues("peekaboot.tracing.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(TraceStore.class);
            assertThat(context).doesNotHaveBean(OtelSpanExporter.class);
        });
    }

    @Test
    void doesNotCreateBeansWhenPeekabootGloballyDisabled() {
        contextRunner.withPropertyValues("peekaboot.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(TraceStore.class);
            assertThat(context).doesNotHaveBean(OtelSpanExporter.class);
        });
    }

    /** Everything that reads the store is servlet-only; a WebFlux or non-web app would fill it for nobody. */
    @Test
    void doesNotCreateBeansWhenNotAServletApplication() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        PeekabootTracingAutoConfiguration.class,
                        OtelTracingAutoConfiguration.class,
                        DispatcherServletAutoConfiguration.class,
                        PeekabootPathsAutoConfiguration.class))
                .withPropertyValues("peekaboot.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
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
                    store.addSpan(TraceFixtures.rootSpanLasting(5));
                    assertThat(store.getTraceCount(TraceBucket.SLOW)).isEqualTo(1);
                });
    }

    @Test
    void startsTheTraceJournalWhileStorageIsOn() {
        storageRunner
                .withPropertyValues("peekaboot.storage.enabled=true", "peekaboot.storage.dir=" + storageDir)
                .run(context ->
                        assertThat(context).hasSingleBean(TraceJournal.class).hasBean("peekabootTraceJournal"));
    }

    @Test
    void startsNoTraceJournalWhileStorageIsOff() {
        storageRunner.withPropertyValues("peekaboot.storage.dir=" + storageDir).run(context -> {
            assertThat(context).hasSingleBean(TraceStoreEventListener.class);
            assertThat(context).doesNotHaveBean(TraceJournal.class);
        });
    }

    @Test
    void startsNoTraceJournalWhenTracesAreSwitchedOff() {
        storageRunner
                .withPropertyValues(
                        "peekaboot.storage.enabled=true",
                        "peekaboot.storage.dir=" + storageDir,
                        "peekaboot.storage.traces.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(TraceJournal.class));
    }

    /** The switch alone starts nothing where no storage auto-configuration resolved a directory. */
    @Test
    void startsNoTraceJournalWithoutAStorageDirectory() {
        contextRunner.withPropertyValues("peekaboot.storage.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(TraceJournal.class);
        });
    }

    /**
     * One context's traces are the next one's, in the buckets they were in: the listener hands its events
     * to the journal, which the next context replays. A live line seen in the store means the restore is
     * done, since the restore gate holds every live input until then.
     */
    @Test
    void theNextContextShowsTheTracesThisOneCaptured() {
        WebApplicationContextRunner persisted = storageRunner.withPropertyValues(
                "peekaboot.storage.enabled=true",
                "peekaboot.storage.dir=" + storageDir,
                "peekaboot.tracing.slow-trace-threshold-ms=1");

        persisted.run(context -> context.publishEvent(new SpanDataEvent(TraceFixtures.rootSpanLasting(5))));

        persisted.run(context -> {
            TraceStore store = context.getBean(TraceStore.class);
            context.publishEvent(TraceFixtures.untracedLog("live"));
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> store.untracedLogs().stream().anyMatch(log -> "live".equals(log.message())));

            assertThat(store.getTrace(TraceFixtures.TRACE_ID)).isPresent();
            assertThat(store.bucketsOf(TraceFixtures.TRACE_ID)).containsExactly(TraceBucket.SLOW);
        });
    }

    /** The next context reads the digest from errors.jsonl, and its replay of the failed trace counts nothing more. */
    @Test
    void theNextContextKeepsTheErrorDigestThisOneTook() {
        WebApplicationContextRunner persisted = storageRunner.withPropertyValues(
                "peekaboot.storage.enabled=true", "peekaboot.storage.dir=" + storageDir);

        persisted.run(context -> {
            context.publishEvent(new SpanDataEvent(TraceFixtures.failedRootSpan()));
            ErrorDigest digest = context.getBean(ErrorDigest.class);
            await().atMost(Duration.ofSeconds(5)).until(() -> !digest.entries().isEmpty());
        });

        persisted.run(context -> {
            TraceStore store = context.getBean(TraceStore.class);
            context.publishEvent(TraceFixtures.untracedLog("live"));
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> store.untracedLogs().stream().anyMatch(log -> "live".equals(log.message())));

            assertThat(store.getTrace(TraceFixtures.TRACE_ID)).isPresent();
            assertThat(context.getBean(ErrorDigest.class).entries())
                    .singleElement()
                    .satisfies(entry -> assertThat(entry.count()).isEqualTo(1));
        });
    }

    /** The digest's file follows the trace journal: with traces switched off the digest counts in memory alone. */
    @Test
    void theErrorDigestStaysInMemoryWhenTracesAreSwitchedOff() {
        storageRunner
                .withPropertyValues(
                        "peekaboot.storage.enabled=true",
                        "peekaboot.storage.dir=" + storageDir,
                        "peekaboot.storage.traces.enabled=false")
                .run(context -> {
                    context.publishEvent(new SpanDataEvent(TraceFixtures.failedRootSpan()));

                    assertThat(context.getBean(ErrorDigest.class).entries()).hasSize(1);
                });

        assertThat(storageDir.resolve("errors.jsonl")).doesNotExist();
    }

    /** Closing folds the journal and says nothing: no line from the journal reaches Logback. */
    @Test
    void closingAContextWithTheJournalLogsNothing() {
        LoggerContext logback = (LoggerContext) LoggerFactory.getILoggerFactory();
        ListAppender<ILoggingEvent> journalLines = new ListAppender<>();
        journalLines.start();
        ch.qos.logback.classic.Logger journalLogger = logback.getLogger("org.peekaboot.backend.tracing.journal");
        journalLogger.addAppender(journalLines);
        try {
            storageRunner
                    .withPropertyValues("peekaboot.storage.enabled=true", "peekaboot.storage.dir=" + storageDir)
                    .run(context -> context.publishEvent(new SpanDataEvent(TraceFixtures.rootSpanLasting(5))));
        } finally {
            journalLogger.detachAppender(journalLines);
        }

        assertThat(journalLines.list).isEmpty();
    }

    /**
     * A host's own tracer provider flushes its batch when the context destroys it. It is destroyed before
     * the journal closes, so the span ended last reaches the next context.
     */
    @Test
    void theSpansAHostTracerProviderFlushesAtCloseReachTheNextContext() {
        WebApplicationContextRunner persisted = storageRunner.withPropertyValues(
                "peekaboot.storage.enabled=true", "peekaboot.storage.dir=" + storageDir);
        AtomicReference<String> traceId = new AtomicReference<>();

        persisted
                .withUserConfiguration(HostTracerProviderConfig.class)
                .run(context -> traceId.set(endSpan(context.getBean(SdkTracerProvider.class))));

        assertTheNextContextHolds(persisted, traceId.get());
    }

    /** As with a provider: a host's span processor bean flushes when destroyed, before the journal closes. */
    @Test
    void theSpansAHostSpanProcessorFlushesAtCloseReachTheNextContext() {
        WebApplicationContextRunner persisted = storageRunner.withPropertyValues(
                "peekaboot.storage.enabled=true", "peekaboot.storage.dir=" + storageDir);
        AtomicReference<String> traceId = new AtomicReference<>();

        persisted.withUserConfiguration(HostSpanProcessorConfig.class).run(context -> {
            SdkTracerProvider unmanaged = SdkTracerProvider.builder()
                    .addSpanProcessor(context.getBean(SpanProcessor.class))
                    .build();
            traceId.set(endSpan(unmanaged));
        });

        assertTheNextContextHolds(persisted, traceId.get());
    }

    /** With no journal running, a host's provider depends on nothing it would miss. */
    @Test
    void aHostTracerProviderStartsWhileNoJournalRuns() {
        storageRunner
                .withPropertyValues("peekaboot.storage.dir=" + storageDir)
                .withUserConfiguration(HostTracerProviderConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(TraceJournal.class);
                    assertThat(context).hasSingleBean(SdkTracerProvider.class);
                });
    }

    /** The span waits in its batch, which flushes only when its provider or processor shuts down. */
    private static String endSpan(SdkTracerProvider provider) {
        Span span = provider.get("peekaboot-test")
                .spanBuilder("ended before the close")
                .startSpan();
        span.end();
        return span.getSpanContext().getTraceId();
    }

    /** A live line seen in the store means the restore is done, since the gate holds live inputs until then. */
    private static void assertTheNextContextHolds(WebApplicationContextRunner persisted, String traceId) {
        persisted.run(context -> {
            TraceStore store = context.getBean(TraceStore.class);
            context.publishEvent(TraceFixtures.untracedLog("live"));
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> store.untracedLogs().stream().anyMatch(log -> "live".equals(log.message())));

            assertThat(store.getTrace(traceId)).isPresent();
        });
    }

    // --- TracingInterceptorAutoConfiguration ---

    @Test
    void registersInterceptorWhenObservationRegistryBeanPresentInWebApp() {
        webContextRunner.withUserConfiguration(ObservationRegistryConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TracingHandlerInterceptor.class);
            assertThat(context).hasSingleBean(WebMvcConfigurer.class);
        });
    }

    @Test
    void doesNotRegisterInterceptorWhenNoObservationRegistryBean() {
        webContextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(TracingHandlerInterceptor.class);
        });
    }

    @Test
    void doesNotRegisterInterceptorWhenNotAWebApplication() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        TracingInterceptorAutoConfiguration.class,
                        DispatcherServletAutoConfiguration.class,
                        PeekabootPathsAutoConfiguration.class))
                .withPropertyValues("peekaboot.enabled=true")
                .withUserConfiguration(ObservationRegistryConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TracingHandlerInterceptor.class);
                });
    }

    @Test
    void doesNotRegisterInterceptorWhenPeekabootDisabled() {
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
    void doesNotRegisterInterceptorWhenTracingDisabled() {
        webContextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .withPropertyValues("peekaboot.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TracingHandlerInterceptor.class);
                });
    }

    @Test
    void registersInterceptorWithExpectedPathPatterns() {
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
                            "/peekaboot/**", "/actuator/**", "/static/**", "/webjars/**", "/error/**");
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

    /** The exporter skips by a span's path tag, which carries the context path; the bean must know it. */
    @Test
    void theContextPathReachesTheSpanExporterExclusions() {
        contextRunner.withPropertyValues("server.servlet.context-path=/app").run(context -> {
            PeekabootPaths paths = context.getBean(PeekabootPaths.class);
            assertThat(paths.isExcludedRequestPath("/app/actuator/health")).isTrue();
            assertThat(paths.isExcludedRequestPath("/app/api/users")).isFalse();
        });
    }

    /** The interceptor beans back off for user-supplied same-named beans, like every other Peekaboot bean. */
    @Test
    void userSuppliedSameNamedInterceptorBeansReplaceTheDefaults() {
        webContextRunner
                .withUserConfiguration(ObservationRegistryConfig.class, UserInterceptorConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("peekabootTracingHandlerInterceptor"))
                            .isSameAs(UserInterceptorConfig.INTERCEPTOR);
                    assertThat(context.getBean("peekabootTracingInterceptorConfigurer"))
                            .isSameAs(UserInterceptorConfig.CONFIGURER);
                });
    }

    @Test
    void aUserSuppliedPeekabootPathsBeanReplacesTheDefault() {
        contextRunner.withUserConfiguration(UserPathsConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(PeekabootPaths.class)).isSameAs(UserPathsConfig.PATHS);
        });
    }

    @Configuration
    static class UserInterceptorConfig {

        static final TracingHandlerInterceptor INTERCEPTOR =
                new TracingHandlerInterceptor(ObservationRegistry.create());
        static final WebMvcConfigurer CONFIGURER = new WebMvcConfigurer() {};

        @Bean
        TracingHandlerInterceptor peekabootTracingHandlerInterceptor() {
            return INTERCEPTOR;
        }

        @Bean
        WebMvcConfigurer peekabootTracingInterceptorConfigurer() {
            return CONFIGURER;
        }
    }

    @Configuration
    static class UserPathsConfig {

        static final PeekabootPaths PATHS = PeekabootPaths.defaults();

        @Bean
        PeekabootPaths peekabootPaths() {
            return PATHS;
        }
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

    @Configuration(proxyBeanMethods = false)
    static class HostTracerProviderConfig {
        @Bean
        SdkTracerProvider hostTracerProvider(OtelSpanExporter exporter) {
            return SdkTracerProvider.builder()
                    .addSpanProcessor(BatchSpanProcessor.builder(exporter)
                            .setScheduleDelay(NO_SCHEDULED_EXPORT)
                            .build())
                    .build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class HostSpanProcessorConfig {
        @Bean
        SpanProcessor hostSpanProcessor(OtelSpanExporter exporter) {
            return BatchSpanProcessor.builder(exporter)
                    .setScheduleDelay(NO_SCHEDULED_EXPORT)
                    .build();
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
