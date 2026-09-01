package org.peekaboot.autoconfigure;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.micrometer.tracing.Tracer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.peekaboot.backend.devtoolbar.ToolbarDataProvider;
import org.peekaboot.backend.filter.DevToolbarFilter;
import org.peekaboot.backend.filter.RequestCaptureFilter;
import org.peekaboot.backend.log.PeekabootLogbackAppender;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Auto-configuration for the development toolbar.
 * Configures filters for toolbar injection and request/log capture.
 */
@AutoConfiguration(
        after = PeekabootAutoConfiguration.class,
        afterName =
                "org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBooleanProperty(PeekabootPropertyKeys.ENABLED)
@ConditionalOnBooleanProperty("peekaboot.dev-toolbar")
@ConditionalOnClass(TraceStore.class)
public class DevToolbarAutoConfiguration {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(DevToolbarAutoConfiguration.class);

    @Bean
    public ToolbarDataProvider toolbarDataProvider() {
        return new ToolbarDataProvider();
    }

    @Bean
    @ConditionalOnBean(Tracer.class)
    public FilterRegistrationBean<DevToolbarFilter> devToolbarFilter(
            ToolbarDataProvider toolbarDataProvider, Tracer tracer) {
        FilterRegistrationBean<DevToolbarFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new DevToolbarFilter(toolbarDataProvider, tracer));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.LOWEST_PRECEDENCE);
        registration.setName("devToolbarFilter");
        log.info("DevToolbarFilter registered for all URLs");
        return registration;
    }

    @Bean
    @ConditionalOnBean(Tracer.class)
    public FilterRegistrationBean<RequestCaptureFilter> requestCaptureFilter(
            Tracer tracer, ApplicationEventPublisher eventPublisher) {
        log.trace("Creating RequestCaptureFilter bean");
        FilterRegistrationBean<RequestCaptureFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RequestCaptureFilter(tracer, eventPublisher));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        registration.setName("requestCaptureFilter");
        log.info("RequestCaptureFilter registered for all URLs");
        return registration;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "ch.qos.logback.classic.LoggerContext")
    static class LogbackCaptureConfiguration {

        @Bean
        LogbackAppenderRegistrar logbackAppenderRegistrar(ApplicationEventPublisher eventPublisher) {
            return new LogbackAppenderRegistrar(eventPublisher);
        }
    }

    public static class LogbackAppenderRegistrar {

        /**
         * The Logback {@code LoggerContext} is JVM-wide while application contexts are not:
         * several can be running at once, and every one that starts makes Spring Boot reset
         * that shared context, detaching the appenders of all the others. The still-running
         * contexts never learn of it, so {@link LogbackCaptureReinstaller} puts them back
         * from here.
         */
        private static final Set<PeekabootLogbackAppender> LIVE_APPENDERS = ConcurrentHashMap.newKeySet();

        private final ApplicationEventPublisher eventPublisher;
        private PeekabootLogbackAppender appender;

        public LogbackAppenderRegistrar(ApplicationEventPublisher eventPublisher) {
            this.eventPublisher = eventPublisher;
        }

        @PostConstruct
        public void registerAppender() {
            if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext loggerContext)) {
                return;
            }

            appender = new PeekabootLogbackAppender();
            appender.setEventPublisher(eventPublisher);
            appender.setContext(loggerContext);
            appender.start();

            Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
            rootLogger.addAppender(appender);
            LIVE_APPENDERS.add(appender);
        }

        @PreDestroy
        public void detachAppender() {
            // the LoggerContext outlives the Spring context (devtools restarts);
            // without detaching, appenders accumulate and pin the closed context
            if (appender == null) {
                return;
            }
            LIVE_APPENDERS.remove(appender);
            if (LoggerFactory.getILoggerFactory() instanceof LoggerContext loggerContext) {
                loggerContext.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(appender);
            }
            appender.stop();
            appender = null;
        }

        /**
         * Re-attaches the appenders of every application context that is still running, after
         * a Logback re-initialisation detached them.
         */
        static void reattachLiveAppenders() {
            if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext loggerContext)) {
                return;
            }
            Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
            for (PeekabootLogbackAppender appender : LIVE_APPENDERS) {
                // a reset stops appenders as well as detaching them, and a stopped appender
                // that is merely re-attached silently discards every event
                appender.setContext(loggerContext);
                appender.start();
                rootLogger.addAppender(appender);
            }
        }
    }
}
