package net.osslabz.peekaboot.backend.tracing.autoconfigure;

import net.osslabz.peekaboot.backend.tracing.bridge.otel.OtelSpanExporter;
import net.osslabz.peekaboot.backend.tracing.store.TraceDataStorage;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for OpenTelemetry span exporter integration.
 * Configures the OtelSpanExporter to capture spans and publish them via Spring events.
 */
@AutoConfiguration(after = PeekabootTracingAutoConfiguration.class)
@ConditionalOnClass(name = "io.opentelemetry.sdk.trace.export.SpanExporter")
@ConditionalOnProperty(prefix = "peekaboot.tracing", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OtelTracingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OtelSpanExporter otelSpanExporter(TraceDataStorage storage, ApplicationEventPublisher eventPublisher) {
        return new OtelSpanExporter(storage, eventPublisher);
    }
}
