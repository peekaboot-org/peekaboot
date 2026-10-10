package org.peekaboot.testsupport;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import java.util.regex.Pattern;

/**
 * An appender filter that keeps one deliberately logged line off an appender. It denies an event
 * only when the logger name equals {@code logger} and the whole formatted message matches the
 * {@code message} regex; every other event stays {@code NEUTRAL}, so the same logger failing in
 * an unexpected way still prints. Being an appender filter, it leaves other appenders (the
 * Peekaboot one under test, on the root logger) receiving the event, which {@link LogCapture}
 * cannot do because it detaches the logger from them.
 */
public final class IntentionalLogFilter extends Filter<ILoggingEvent> {

    private String logger;
    private Pattern message;

    public void setLogger(String logger) {
        this.logger = logger;
    }

    public void setMessage(String message) {
        this.message = Pattern.compile(message);
    }

    @Override
    public void start() {
        if (logger == null || message == null) {
            addError("IntentionalLogFilter needs both a logger and a message");
            return;
        }
        super.start();
    }

    @Override
    public FilterReply decide(ILoggingEvent event) {
        String formatted = event.getFormattedMessage();
        boolean intentional = event.getLoggerName().equals(logger)
                && formatted != null
                && message.matcher(formatted).matches();
        return intentional ? FilterReply.DENY : FilterReply.NEUTRAL;
    }
}
