package org.peekaboot.backend.integration;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.peekaboot.backend.devtoolbar.ToolbarDataProvider;
import org.peekaboot.backend.filter.DevToolbarFilter;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.peekaboot.backend.tracing.store.TraceStoreEventListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

import java.time.Duration;
import java.util.UUID;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Shared test configuration for dev toolbar integration tests.
 * Provides common bean definitions for trace storage and toolbar injection.
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
    ToolbarDataProvider toolbarDataProvider() {
        return new ToolbarDataProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    Tracer testTracer() {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);

        // Generate a valid hex trace ID for each call
        lenient().when(context.traceId()).thenAnswer(inv -> UUID.randomUUID().toString().replace("-", "").substring(0, 32));
        lenient().when(context.spanId()).thenAnswer(inv -> UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        lenient().when(span.context()).thenReturn(context);
        lenient().when(tracer.currentSpan()).thenReturn(span);

        return tracer;
    }

    @Bean
    FilterRegistrationBean<DevToolbarFilter> devToolbarFilter(
            ToolbarDataProvider toolbarDataProvider,
            Tracer tracer) {
        FilterRegistrationBean<DevToolbarFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new DevToolbarFilter(toolbarDataProvider, tracer));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.LOWEST_PRECEDENCE);
        registration.setName("devToolbarFilter");
        return registration;
    }
}
