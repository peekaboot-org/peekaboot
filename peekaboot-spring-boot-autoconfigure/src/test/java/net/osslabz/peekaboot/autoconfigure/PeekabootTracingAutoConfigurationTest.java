package net.osslabz.peekaboot.autoconfigure;

import net.osslabz.peekaboot.backend.tracing.autoconfigure.PeekabootTracingProperties;
import net.osslabz.peekaboot.backend.tracing.bridge.otel.OtelSpanExporter;
import net.osslabz.peekaboot.backend.tracing.query.TraceQueryService;
import net.osslabz.peekaboot.backend.tracing.store.TraceStore;
import net.osslabz.peekaboot.backend.tracing.store.TraceStoreEventListener;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
            assertThat(context).hasSingleBean(TraceQueryService.class);
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
                    assertThat(context).doesNotHaveBean(TraceQueryService.class);
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
}
