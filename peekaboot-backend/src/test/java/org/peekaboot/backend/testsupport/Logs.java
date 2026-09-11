package org.peekaboot.backend.testsupport;

import java.time.Instant;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;

/**
 * Builds {@link LogCapturedEvent} fixtures: an INFO line from {@code TestLogger} on thread
 * {@code main}, emitted in span {@code span1} at the epoch, unless a test says otherwise.
 */
public final class Logs {

    private Logs() {}

    public static LogBuilder log(String traceId) {
        return new LogBuilder(traceId);
    }

    public static final class LogBuilder {

        private final String traceId;
        private String spanId = "span1";
        private Instant timestamp = Instant.EPOCH;
        private String level = "INFO";
        private String loggerName = "TestLogger";
        private String message = "message";

        private LogBuilder(String traceId) {
            this.traceId = traceId;
        }

        /** {@code null} for a line captured outside any span. */
        public LogBuilder inSpan(String spanId) {
            this.spanId = spanId;
            return this;
        }

        public LogBuilder at(String level) {
            this.level = level;
            return this;
        }

        public LogBuilder from(String loggerName) {
            this.loggerName = loggerName;
            return this;
        }

        public LogBuilder saying(String message) {
            this.message = message;
            return this;
        }

        public LogBuilder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public LogCapturedEvent build() {
            return new LogCapturedEvent(traceId, spanId, timestamp, level, loggerName, message, "main");
        }
    }
}
