package org.peekaboot.autoconfigure;

import io.micrometer.tracing.Tracer;
import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.devtoolbar.ToolbarDataProvider;
import org.peekaboot.backend.filter.DevToolbarFilter;
import org.peekaboot.backend.filter.RequestCaptureFilter;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * The toolbar injection filter, and the request and log capture that feed the store. The
 * capture parts need a {@link TraceStore} bean to land in; the toolbar itself only needs
 * the {@link Tracer}, whichever of Boot's two tracing bridges registers it.
 */
@AutoConfiguration(
        after = {PeekabootAutoConfiguration.class, PeekabootTracingAutoConfiguration.class},
        afterName = {
            "org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration",
            "org.springframework.boot.micrometer.tracing.brave.autoconfigure.BraveAutoConfiguration"
        })
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBooleanProperty(PeekabootPropertyKeys.ENABLED)
@ConditionalOnBooleanProperty(PeekabootPropertyKeys.DEV_TOOLBAR)
public class DevToolbarAutoConfiguration {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(DevToolbarAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public ToolbarDataProvider toolbarDataProvider() {
        return new ToolbarDataProvider();
    }

    // the missing-bean check matches the deduced generic FilterRegistrationBean<DevToolbarFilter>,
    // so the application's other filter registrations never back this one off
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(Tracer.class)
    public FilterRegistrationBean<DevToolbarFilter> devToolbarFilter(
            ToolbarDataProvider toolbarDataProvider,
            Tracer tracer,
            PeekabootPaths peekabootPaths,
            Environment environment) {
        FilterRegistrationBean<DevToolbarFilter> registration = new FilterRegistrationBean<>();
        // read via the Environment: springdoc's SwaggerUiConfigProperties may not be on the classpath
        registration.setFilter(new DevToolbarFilter(
                toolbarDataProvider,
                tracer,
                peekabootPaths,
                environment.getProperty("springdoc.swagger-ui.path", PeekabootPaths.DEFAULT_SWAGGER_UI_PATH)));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.LOWEST_PRECEDENCE);
        registration.setName("devToolbarFilter");
        log.debug("DevToolbarFilter registered for all URLs");
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({Tracer.class, TraceStore.class})
    public FilterRegistrationBean<RequestCaptureFilter> requestCaptureFilter(
            Tracer tracer,
            ApplicationEventPublisher eventPublisher,
            ObjectProvider<MaskingEngine> maskingEngine,
            PeekabootPaths peekabootPaths) {
        FilterRegistrationBean<RequestCaptureFilter> registration = new FilterRegistrationBean<>();
        // The shared bean when the dashboard is up; a private instance when the toolbar
        // runs without PeekabootAutoConfiguration (no actuator endpoint classes).
        registration.setFilter(new RequestCaptureFilter(
                tracer, eventPublisher, maskingEngine.getIfAvailable(MaskingEngine::new), peekabootPaths));
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
