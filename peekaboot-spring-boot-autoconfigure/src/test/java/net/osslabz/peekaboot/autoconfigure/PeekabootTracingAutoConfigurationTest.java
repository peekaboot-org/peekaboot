package net.osslabz.peekaboot.autoconfigure;

import net.osslabz.peekaboot.backend.tracing.autoconfigure.PeekabootTracingProperties;
import net.osslabz.peekaboot.backend.tracing.bridge.otel.OtelSpanExporter;
import net.osslabz.peekaboot.backend.tracing.store.SpanData;
import net.osslabz.peekaboot.backend.tracing.store.TraceBucket;
import net.osslabz.peekaboot.backend.tracing.store.TraceStore;
import net.osslabz.peekaboot.backend.tracing.store.TraceStoreEventListener;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PeekabootTracingAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PeekabootTracingAutoConfiguration.class,
                    OtelTracingAutoConfiguration.class
            ));

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
        contextRunner
                .withPropertyValues("peekaboot.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(TraceStore.class);
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
    void bucketPropertiesReachTheStore() {
        contextRunner
                .withPropertyValues(
                        "peekaboot.tracing.max-error-traces=1",
                        "peekaboot.tracing.slow-trace-threshold-ms=1")
                .run(context -> {
                    TraceStore store = context.getBean(TraceStore.class);
                    // slow threshold 1ms: a 5ms span classifies as slow
                    Instant start = Instant.parse("2026-01-01T00:00:00Z");
                    store.addSpan(new SpanData("t1", "s1", null, "op", null,
                            start, start.plusMillis(5), Duration.ofMillis(5),
                            Map.of(), List.of(), null, null, null, null, null, List.of(),
                            store.nextCreationOrder()));
                    assertThat(store.getTraceCount(TraceBucket.SLOW)).isEqualTo(1);
                });
    }
}
