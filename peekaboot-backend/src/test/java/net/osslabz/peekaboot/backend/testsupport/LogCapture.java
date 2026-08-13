package net.osslabz.peekaboot.backend.testsupport;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

/**
 * Attaches/detaches a logback {@link ListAppender} to a class's logger so tests can assert on a
 * log event instead of letting it reach the console. Shared by every {@code peekaboot-backend}
 * test that exercises a deliberate WARN/DEBUG logging path; each call site attaches in a
 * try-block and detaches in a {@code finally}.
 */
public final class LogCapture {

    private LogCapture() {
    }

    public static ListAppender<ILoggingEvent> attach(Class<?> loggerClass) {
        return attach(loggerClass, null);
    }

    /**
     * @param level if non-null, raised on the logger before attaching (e.g. to capture DEBUG
     *              events); pair with {@link #detach(Class, ListAppender, boolean)} to reset it.
     */
    public static ListAppender<ILoggingEvent> attach(Class<?> loggerClass, Level level) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerClass);
        if (level != null) {
            logger.setLevel(level);
        }
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setAdditive(false);
        return appender;
    }

    public static void detach(Class<?> loggerClass, ListAppender<ILoggingEvent> appender) {
        detach(loggerClass, appender, false);
    }

    public static void detach(Class<?> loggerClass, ListAppender<ILoggingEvent> appender, boolean resetLevel) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerClass);
        logger.detachAppender(appender);
        logger.setAdditive(true);
        if (resetLevel) {
            logger.setLevel(null);
        }
    }
}
