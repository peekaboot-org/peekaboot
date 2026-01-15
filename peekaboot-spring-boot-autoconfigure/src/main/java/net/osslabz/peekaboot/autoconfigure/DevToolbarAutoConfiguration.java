package net.osslabz.peekaboot.autoconfigure;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.micrometer.tracing.Tracer;
import jakarta.annotation.PostConstruct;
import net.osslabz.peekaboot.backend.config.PeekabootProperties;
import net.osslabz.peekaboot.backend.devtoolbar.ToolbarDataProvider;
import net.osslabz.peekaboot.backend.filter.DevToolbarFilter;
import net.osslabz.peekaboot.backend.filter.RequestCaptureFilter;
import net.osslabz.peekaboot.backend.log.PeekabootLogbackAppender;
import net.osslabz.peekaboot.backend.tracing.query.TraceQueryService;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Auto-configuration for the development toolbar.
 * Configures filters for toolbar injection and request/log capture.
 */
@AutoConfiguration(
        after = PeekabootAutoConfiguration.class,
        afterName = "org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration"
)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "peekaboot", name = "dev-toolbar", havingValue = "true")
@ConditionalOnClass(TraceQueryService.class)
public class DevToolbarAutoConfiguration {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(DevToolbarAutoConfiguration.class);

    @Bean
    public ToolbarDataProvider toolbarDataProvider(PeekabootProperties properties) {
        log.trace("Creating ToolbarDataProvider bean with basePath: {}", properties.getBasePath());
        return new ToolbarDataProvider(properties.getBasePath());
    }

    @Bean
    @ConditionalOnBean(Tracer.class)
    public FilterRegistrationBean<DevToolbarFilter> devToolbarFilter(
            ToolbarDataProvider toolbarDataProvider,
            Tracer tracer,
            PeekabootProperties properties) {
        log.trace("Creating DevToolbarFilter bean with basePath: {}", properties.getBasePath());
        FilterRegistrationBean<DevToolbarFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new DevToolbarFilter(toolbarDataProvider, tracer, properties.getBasePath()));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.LOWEST_PRECEDENCE);
        registration.setName("devToolbarFilter");
        log.info("DevToolbarFilter registered for all URLs");
        return registration;
    }

    @Bean
    @ConditionalOnBean(Tracer.class)
    public FilterRegistrationBean<RequestCaptureFilter> requestCaptureFilter(
            Tracer tracer,
            ApplicationEventPublisher eventPublisher) {
        log.trace("Creating RequestCaptureFilter bean");
        FilterRegistrationBean<RequestCaptureFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RequestCaptureFilter(tracer, eventPublisher));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        registration.setName("requestCaptureFilter");
        log.info("RequestCaptureFilter registered for all URLs");
        return registration;
    }

    @Bean
    public LogbackAppenderRegistrar logbackAppenderRegistrar(ApplicationEventPublisher eventPublisher) {
        return new LogbackAppenderRegistrar(eventPublisher);
    }

    public static class LogbackAppenderRegistrar {
        private final ApplicationEventPublisher eventPublisher;

        public LogbackAppenderRegistrar(ApplicationEventPublisher eventPublisher) {
            this.eventPublisher = eventPublisher;
        }

        @PostConstruct
        public void registerAppender() {
            if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext loggerContext)) {
                return;
            }

            PeekabootLogbackAppender appender = new PeekabootLogbackAppender();
            appender.setEventPublisher(eventPublisher);
            appender.setContext(loggerContext);
            appender.start();

            Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
            rootLogger.addAppender(appender);
        }
    }
}
