package org.peekaboot.backend.testsupport;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

/**
 * Attaches a logback {@link ListAppender} to a class's logger so tests can assert on a log event
 * instead of letting it reach the console. Shared by every {@code peekaboot-backend} test that
 * exercises a deliberate WARN/DEBUG logging path. {@code AutoCloseable} so call sites use
 * try-with-resources; {@link #close()} stops the appender, detaches it, and restores the
 * logger's additivity and level to whatever they were before {@link #attach} ran.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender;
    private final boolean previousAdditive;
    private final Level previousLevel;

    private LogCapture(Logger logger, ListAppender<ILoggingEvent> appender,
                        boolean previousAdditive, Level previousLevel) {
        this.logger = logger;
        this.appender = appender;
        this.previousAdditive = previousAdditive;
        this.previousLevel = previousLevel;
    }

    public static LogCapture attach(Class<?> loggerClass) {
        return attach(loggerClass, null);
    }

    /**
     * @param level if non-null, raised on the logger before attaching (e.g. to capture DEBUG
     *              events); the logger's original level is restored on {@link #close()}.
     */
    public static LogCapture attach(Class<?> loggerClass, Level level) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerClass);
        boolean previousAdditive = logger.isAdditive();
        Level previousLevel = logger.getLevel();
        if (level != null) {
            logger.setLevel(level);
        }
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setAdditive(false);
        return new LogCapture(logger, appender, previousAdditive, previousLevel);
    }

    /** The captured events; asserted on as {@code capture.appender().list}. */
    public ListAppender<ILoggingEvent> appender() {
        return appender;
    }

    @Override
    public void close() {
        appender.stop();
        logger.detachAppender(appender);
        logger.setAdditive(previousAdditive);
        logger.setLevel(previousLevel);
    }
}
