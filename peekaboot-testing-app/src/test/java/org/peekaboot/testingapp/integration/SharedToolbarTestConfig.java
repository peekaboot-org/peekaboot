package org.peekaboot.testingapp.integration;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.peekaboot.backend.tracing.store.TraceStoreEventListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.UUID;

import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Shared test configuration for dev toolbar integration tests.
 * Supplies a deterministic mock {@link Tracer} and a small-capacity
 * {@link TraceStore}; the toolbar-injection beans themselves
 * (ToolbarDataProvider, the devToolbarFilter registration) come from the
 * real {@code DevToolbarAutoConfiguration} now that these tests boot the
 * real sample app, so this config no longer redefines them.
 */
@TestConfiguration
public class SharedToolbarTestConfig {

    @Bean
    TraceStore traceStore() {
        return new InMemoryTraceStore(100, 50, Duration.ofMinutes(5));
    }

    @Bean
    TraceStoreEventListener traceStoreEventListener(TraceStore traceStore) {
        return new TraceStoreEventListener(traceStore);
    }

    @Bean
    @ConditionalOnMissingBean
    Tracer testTracer() {
        // Deep stubs so the real datasource-micrometer instrumentation (a genuine
        // main dependency here, unlike under the old backend-only fixture) can call
        // unstubbed methods like spanBuilder() without NPEs; only currentSpan() is
        // given a fixed, meaningful answer below.
        Tracer tracer = mock(Tracer.class, RETURNS_DEEP_STUBS);
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);

        // Generate a valid hex trace ID for each call
        lenient().when(context.traceId()).thenAnswer(inv -> UUID.randomUUID().toString().replace("-", "").substring(0, 32));
        lenient().when(context.spanId()).thenAnswer(inv -> UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        lenient().when(span.context()).thenReturn(context);
        lenient().when(tracer.currentSpan()).thenReturn(span);

        return tracer;
    }
}
