package org.peekaboot.autoconfigure;

import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.tracing.bridge.otel.OtelSpanExporter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

/** Guarded by class name: the OpenTelemetry SDK is only an optional dependency of peekaboot-backend. */
@AutoConfiguration(after = PeekabootTracingAutoConfiguration.class)
@ConditionalOnPeekabootTracing
@ConditionalOnClass(name = "io.opentelemetry.sdk.trace.export.SpanExporter")
public class OtelTracingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OtelSpanExporter otelSpanExporter(ApplicationEventPublisher eventPublisher, PeekabootPaths peekabootPaths) {
        return new OtelSpanExporter(eventPublisher, peekabootPaths);
    }
}
