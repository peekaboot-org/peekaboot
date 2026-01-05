package net.osslabz.peekaboot.tracing.autoconfigure;

import io.micrometer.tracing.Tracer;
import net.osslabz.peekaboot.tracing.InMemoryTracer;
import net.osslabz.peekaboot.tracing.bridge.brave.BraveSpanHandler;
import net.osslabz.peekaboot.tracing.bridge.otel.OtelSpanExporter;
import net.osslabz.peekaboot.tracing.query.TraceQueryService;
import net.osslabz.peekaboot.tracing.store.InMemorySpanStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class PeekabootTracingAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PeekabootTracingAutoConfiguration.class,
                    StandaloneTracingAutoConfiguration.class,
                    BraveTracingAutoConfiguration.class,
                    OtelTracingAutoConfiguration.class
            ));

    @Test
    void shouldCreateCoreBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(InMemorySpanStore.class);
            assertThat(context).hasSingleBean(TraceQueryService.class);
        });
    }

    @Test
    void shouldCreateBraveSpanHandlerWhenBraveOnClasspath() {
        // Brave is on classpath in this test project
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(BraveSpanHandler.class);
        });
    }

    @Test
    void shouldCreateOtelSpanExporterWhenOtelOnClasspath() {
        // OTel is on classpath in this test project
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(OtelSpanExporter.class);
        });
    }

    @Test
    void shouldNotCreateStandaloneTracerWhenBraveOrOtelOnClasspath() {
        // Since Brave and OTel are on classpath, StandaloneTracingAutoConfiguration should not apply
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(InMemoryTracer.class);
        });
    }

    @Test
    void shouldNotCreateBeansWhenDisabled() {
        contextRunner
                .withPropertyValues("peekaboot.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(InMemorySpanStore.class);
                    assertThat(context).doesNotHaveBean(BraveSpanHandler.class);
                    assertThat(context).doesNotHaveBean(OtelSpanExporter.class);
                });
    }

    @Test
    void shouldApplyCustomProperties() {
        contextRunner
                .withPropertyValues(
                        "peekaboot.tracing.max-traces=500",
                        "peekaboot.tracing.max-spans-per-trace=25"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(PeekabootTracingProperties.class);
                    PeekabootTracingProperties properties = context.getBean(PeekabootTracingProperties.class);
                    assertThat(properties.getMaxTraces()).isEqualTo(500);
                    assertThat(properties.getMaxSpansPerTrace()).isEqualTo(25);
                });
    }

    @Test
    void shouldNotOverrideExistingTracerBean() {
        contextRunner
                .withBean("customTracer", Tracer.class, () -> new CustomTracer())
                .run(context -> {
                    assertThat(context).hasSingleBean(Tracer.class);
                    assertThat(context.getBean(Tracer.class)).isInstanceOf(CustomTracer.class);
                    assertThat(context).doesNotHaveBean(InMemoryTracer.class);
                });
    }

    private static class CustomTracer implements Tracer {
        @Override public io.micrometer.tracing.Span nextSpan() { return null; }
        @Override public io.micrometer.tracing.Span nextSpan(io.micrometer.tracing.Span parent) { return null; }
        @Override public SpanInScope withSpan(io.micrometer.tracing.Span span) { return null; }
        @Override public io.micrometer.tracing.ScopedSpan startScopedSpan(String name) { return null; }
        @Override public io.micrometer.tracing.Span.Builder spanBuilder() { return null; }
        @Override public io.micrometer.tracing.TraceContext.Builder traceContextBuilder() { return null; }
        @Override public io.micrometer.tracing.CurrentTraceContext currentTraceContext() { return null; }
        @Override public io.micrometer.tracing.SpanCustomizer currentSpanCustomizer() { return null; }
        @Override public io.micrometer.tracing.Span currentSpan() { return null; }
        @Override public java.util.Map<String, String> getAllBaggage() { return null; }
        @Override public io.micrometer.tracing.Baggage getBaggage(String name) { return null; }
        @Override public io.micrometer.tracing.Baggage getBaggage(io.micrometer.tracing.TraceContext traceContext, String name) { return null; }
        @Override public io.micrometer.tracing.Baggage createBaggage(String name) { return null; }
        @Override public io.micrometer.tracing.Baggage createBaggage(String name, String value) { return null; }
        @Override public io.micrometer.tracing.BaggageInScope createBaggageInScope(String name, String value) { return null; }
        @Override public io.micrometer.tracing.BaggageInScope createBaggageInScope(io.micrometer.tracing.TraceContext traceContext, String name, String value) { return null; }
    }
}
