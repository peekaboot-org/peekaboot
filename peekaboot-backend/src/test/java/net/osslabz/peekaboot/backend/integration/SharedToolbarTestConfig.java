package net.osslabz.peekaboot.backend.integration;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import net.osslabz.peekaboot.backend.config.PeekabootProperties;
import net.osslabz.peekaboot.backend.devtoolbar.ToolbarDataProvider;
import net.osslabz.peekaboot.backend.filter.DevToolbarFilter;
import net.osslabz.peekaboot.backend.service.PeekabookActuatorService;
import net.osslabz.peekaboot.backend.tracing.query.TraceQueryService;
import net.osslabz.peekaboot.backend.tracing.store.TraceDataStorage;
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
 * Provides common bean definitions for trace storage, querying, and toolbar injection.
 */
@TestConfiguration
public class SharedToolbarTestConfig {

    @Bean
    TraceDataStorage traceDataStorage() {
        return new TraceDataStorage(100, 50, Duration.ofMinutes(5));
    }

    @Bean
    TraceQueryService traceQueryService(TraceDataStorage storage) {
        return new TraceQueryService(storage);
    }

    @Bean
    ToolbarDataProvider toolbarDataProvider(
            TraceQueryService traceQueryService,
            PeekabookActuatorService actuatorService,
            PeekabootProperties properties) {
        return new ToolbarDataProvider(traceQueryService, actuatorService, properties.getBasePath());
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
            Tracer tracer,
            PeekabootProperties properties) {
        FilterRegistrationBean<DevToolbarFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new DevToolbarFilter(toolbarDataProvider, tracer, properties.getBasePath()));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.LOWEST_PRECEDENCE);
        registration.setName("devToolbarFilter");
        return registration;
    }
}
