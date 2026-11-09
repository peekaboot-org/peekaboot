package org.peekaboot.autoconfigure;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.peekaboot.backend.tracing.capture.PeekabootLogbackAppender;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;

/**
 * Attaches the log-capture appender to the root logger until the application context starts
 * closing, and keeps the JVM-wide bookkeeping {@link LogbackCaptureReinstaller} needs to
 * put every live appender back after Boot re-initialises Logback.
 */
class LogbackAppenderRegistrar implements ApplicationListener<ContextClosedEvent> {

    /**
     * JVM-wide, like the {@code LoggerContext}: the appender of every running application
     * context, for {@link LogbackCaptureReinstaller} to put back after a reset.
     */
    private static final Set<PeekabootLogbackAppender> LIVE_APPENDERS = ConcurrentHashMap.newKeySet();

    private final ApplicationContext applicationContext;
    private PeekabootLogbackAppender appender;

    LogbackAppenderRegistrar(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    void registerAppender() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext loggerContext)) {
            return;
        }

        appender = new PeekabootLogbackAppender(applicationContext);
        appender.setContext(loggerContext);
        appender.start();

        Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.addAppender(appender);
        LIVE_APPENDERS.add(appender);
    }

    /**
     * Detaches before the context destroys a bean, since a line reaching the store's listener
     * after that would make the closing context rebuild it.
     */
    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        // a child context's close reaches its parent's listeners too
        if (applicationContext.equals(event.getApplicationContext())) {
            detachAppender();
        }
    }

    /** Run async, the detach could come after the beans are destroyed, which is the order the close event exists to prevent. */
    @Override
    public boolean supportsAsyncExecution() {
        return false;
    }

    /** The fallback for a context whose refresh failed, which publishes no ContextClosedEvent. */
    @PreDestroy
    void detachAppender() {
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
