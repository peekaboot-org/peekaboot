package net.osslabz.peekaboot.tracing.autoconfigure;

import io.micrometer.tracing.Tracer;
import net.osslabz.peekaboot.tracing.InMemoryTracer;
import net.osslabz.peekaboot.tracing.context.InMemoryCurrentTraceContext;
import net.osslabz.peekaboot.tracing.store.InMemorySpanStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = PeekabootTracingAutoConfiguration.class)
@ConditionalOnClass(Tracer.class)
@ConditionalOnMissingClass({"brave.handler.SpanHandler", "io.opentelemetry.sdk.trace.export.SpanExporter"})
@ConditionalOnProperty(prefix = "peekaboot.tracing", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StandaloneTracingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public InMemoryCurrentTraceContext inMemoryCurrentTraceContext() {
        return new InMemoryCurrentTraceContext();
    }

    @Bean
    @ConditionalOnMissingBean(Tracer.class)
    public InMemoryTracer inMemoryTracer(InMemorySpanStore store, InMemoryCurrentTraceContext currentTraceContext) {
        return new InMemoryTracer(store, currentTraceContext);
    }
}
