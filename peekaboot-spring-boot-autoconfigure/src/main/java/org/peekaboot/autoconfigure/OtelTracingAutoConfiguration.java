package org.peekaboot.autoconfigure;

import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.tracing.bridge.otel.OtelSpanExporter;
import org.peekaboot.backend.tracing.journal.TraceJournal;
import org.springframework.boot.autoconfigure.AbstractDependsOnBeanFactoryPostProcessor;
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
    public OtelSpanExporter peekabootOtelSpanExporter(
            ApplicationEventPublisher eventPublisher, PeekabootPaths peekabootPaths) {
        return new OtelSpanExporter(eventPublisher, peekabootPaths);
    }

    /**
     * A tracer provider exports the spans still in its batch when the context destroys it, which comes
     * after the journal's close unless it depends on the journal. Any provider counts, the host's own too.
     */
    @Bean
    static DependsOnTraceJournal peekabootTracerProvidersDependOnTraceJournal() {
        return new DependsOnTraceJournal(SdkTracerProvider.class);
    }

    /** As for a tracer provider: a span processor bean exports its batch when destroyed. */
    @Bean
    static DependsOnTraceJournal peekabootSpanProcessorsDependOnTraceJournal() {
        return new DependsOnTraceJournal(SpanProcessor.class);
    }

    /** Makes every bean of one type depend on the trace journal, where one runs; without one it changes nothing. */
    static final class DependsOnTraceJournal extends AbstractDependsOnBeanFactoryPostProcessor {

        DependsOnTraceJournal(Class<?> beanClass) {
            super(beanClass, TraceJournal.class);
        }
    }
}
