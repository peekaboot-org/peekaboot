package net.osslabz.peekaboot.autoconfigure;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import jakarta.annotation.PostConstruct;
import net.osslabz.peekaboot.backend.config.PeekabootProperties;
import net.osslabz.peekaboot.backend.devtoolbar.ToolbarDataProvider;
import net.osslabz.peekaboot.backend.filter.DevToolbarFilter;
import net.osslabz.peekaboot.backend.filter.RequestCaptureFilter;
import net.osslabz.peekaboot.backend.log.PeekabootLogbackAppender;
import net.osslabz.peekaboot.backend.tracing.event.TraceEventBus;
import net.osslabz.peekaboot.backend.service.PeekabookActuatorService;
import net.osslabz.peekaboot.backend.tracing.query.TraceQueryService;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

@AutoConfiguration(after = PeekabootAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "peekaboot", name = "dev-toolbar", havingValue = "true")
@ConditionalOnClass(TraceQueryService.class)
public class DevToolbarAutoConfiguration {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(DevToolbarAutoConfiguration.class);

    @Bean
    public ToolbarDataProvider toolbarDataProvider(
            TraceQueryService traceQueryService,
            PeekabookActuatorService actuatorService,
            PeekabootProperties properties) {
        log.trace("Creating ToolbarDataProvider bean with basePath: {}", properties.getBasePath());
        return new ToolbarDataProvider(traceQueryService, actuatorService, properties.getBasePath());
    }

    @Bean
    public FilterRegistrationBean<DevToolbarFilter> devToolbarFilter(
            ToolbarDataProvider toolbarDataProvider,
            PeekabootProperties properties) {
        log.trace("Creating DevToolbarFilter bean with basePath: {}", properties.getBasePath());
        FilterRegistrationBean<DevToolbarFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new DevToolbarFilter(toolbarDataProvider, properties.getBasePath()));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.LOWEST_PRECEDENCE);
        registration.setName("devToolbarFilter");
        log.info("DevToolbarFilter registered for all URLs");
        return registration;
    }

    @Bean
    public FilterRegistrationBean<RequestCaptureFilter> requestCaptureFilter(TraceEventBus eventBus) {
        log.trace("Creating RequestCaptureFilter bean");
        FilterRegistrationBean<RequestCaptureFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RequestCaptureFilter(eventBus));
        registration.addUrlPatterns("/*");
        // Run early to capture before other filters modify request/response
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        registration.setName("requestCaptureFilter");
        log.info("RequestCaptureFilter registered for all URLs");
        return registration;
    }

    @Bean
    public LogbackAppenderRegistrar logbackAppenderRegistrar(TraceEventBus eventBus) {
        return new LogbackAppenderRegistrar(eventBus);
    }

    public static class LogbackAppenderRegistrar {
        private final TraceEventBus eventBus;

        public LogbackAppenderRegistrar(TraceEventBus eventBus) {
            this.eventBus = eventBus;
        }

        @PostConstruct
        public void registerAppender() {
            if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext loggerContext)) {
                return;
            }

            PeekabootLogbackAppender appender = new PeekabootLogbackAppender();
            appender.setEventBus(eventBus);
            appender.setContext(loggerContext);
            appender.start();

            Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
            rootLogger.addAppender(appender);
        }
    }
}
