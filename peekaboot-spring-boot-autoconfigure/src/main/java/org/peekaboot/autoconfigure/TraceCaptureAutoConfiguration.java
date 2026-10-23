package org.peekaboot.autoconfigure;

import io.micrometer.tracing.Tracer;
import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.filter.RequestCaptureFilter;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;

/**
 * The request and log capture that feed the {@link TraceStore}. The dashboard reads that store
 * as much as the toolbar does, so the capture follows tracing rather than the toolbar switch.
 */
@AutoConfiguration(
        after = {PeekabootAutoConfiguration.class, PeekabootTracingAutoConfiguration.class},
        afterName = {
            "org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration",
            "org.springframework.boot.micrometer.tracing.brave.autoconfigure.BraveAutoConfiguration"
        })
@ConditionalOnPeekabootTracing
@Import(MaskingConfiguration.class)
public class TraceCaptureAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TraceCaptureAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({Tracer.class, TraceStore.class})
    public FilterRegistrationBean<RequestCaptureFilter> requestCaptureFilter(
            Tracer tracer,
            ApplicationEventPublisher eventPublisher,
            MaskingEngine maskingEngine,
            PeekabootPaths peekabootPaths) {
        FilterRegistrationBean<RequestCaptureFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RequestCaptureFilter(tracer, eventPublisher, maskingEngine, peekabootPaths));
        registration.addUrlPatterns("/*");
        // inside Boot's ServerHttpObservationFilter (HIGHEST_PRECEDENCE + 1), so the server
        // span is current, and ahead of Spring Security, so rejected requests are captured too
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        registration.setName("requestCaptureFilter");
        log.debug("RequestCaptureFilter registered for all URLs");
        return registration;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "ch.qos.logback.classic.LoggerContext")
    @ConditionalOnBean(TraceStore.class)
    static class LogbackCaptureConfiguration {

        @Bean
        @ConditionalOnMissingBean
        LogbackAppenderRegistrar logbackAppenderRegistrar(ApplicationEventPublisher eventPublisher) {
            return new LogbackAppenderRegistrar(eventPublisher);
        }
    }
}
