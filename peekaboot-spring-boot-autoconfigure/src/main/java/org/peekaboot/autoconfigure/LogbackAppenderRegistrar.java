package org.peekaboot.autoconfigure;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.peekaboot.backend.log.PeekabootLogbackAppender;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Attaches the log-capture appender to the root logger for the life of the application
 * context, and keeps the JVM-wide bookkeeping {@link LogbackCaptureReinstaller} needs to
 * put every live appender back after Boot re-initialises Logback.
 */
class LogbackAppenderRegistrar {

    /**
     * JVM-wide, like the {@code LoggerContext}: the appender of every running application
     * context, for {@link LogbackCaptureReinstaller} to put back after a reset.
     */
    private static final Set<PeekabootLogbackAppender> LIVE_APPENDERS = ConcurrentHashMap.newKeySet();

    private final ApplicationEventPublisher eventPublisher;
    private PeekabootLogbackAppender appender;

    LogbackAppenderRegistrar(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @PostConstruct
    void registerAppender() {
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
