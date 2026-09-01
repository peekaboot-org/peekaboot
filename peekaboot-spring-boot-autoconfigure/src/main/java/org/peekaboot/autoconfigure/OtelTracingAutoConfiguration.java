package org.peekaboot.autoconfigure;

import org.peekaboot.backend.tracing.bridge.otel.OtelSpanExporter;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for OpenTelemetry span exporter integration.
 * Configures the OtelSpanExporter to capture spans and publish them via Spring events.
 */
@AutoConfiguration(after = PeekabootTracingAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "io.opentelemetry.sdk.trace.export.SpanExporter")
@ConditionalOnProperty(prefix = "peekaboot", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "peekaboot.tracing", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OtelTracingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OtelSpanExporter otelSpanExporter(TraceStore storage, ApplicationEventPublisher eventPublisher) {
        return new OtelSpanExporter(storage, eventPublisher);
    }
}
