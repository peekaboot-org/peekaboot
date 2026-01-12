package net.osslabz.peekaboot.tracing.autoconfigure;

import net.osslabz.peekaboot.tracing.bridge.otel.OtelSpanExporter;
import net.osslabz.peekaboot.tracing.event.TraceEventBus;
import net.osslabz.peekaboot.tracing.store.InMemorySpanStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = PeekabootTracingAutoConfiguration.class)
@ConditionalOnClass(name = "io.opentelemetry.sdk.trace.export.SpanExporter")
@ConditionalOnProperty(prefix = "peekaboot.tracing", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OtelTracingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OtelSpanExporter otelSpanExporter(InMemorySpanStore store, TraceEventBus eventBus) {
        return new OtelSpanExporter(store, eventBus);
    }
}
