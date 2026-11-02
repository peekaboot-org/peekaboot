package org.peekaboot.backend.tracing.journal;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;
import org.peekaboot.backend.tracing.event.SpanData;

/**
 * One line of the trace journal or of its snapshot. Each file begins with its header, which carries
 * the schema version; every line after a run line belongs to that run. An input line holds the event
 * as the store received it, raw: nothing is masked before it is written. A compact constructor that
 * finds a required field missing refuses the line, so the reader skips a line the store could not take.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = JournalLine.SnapshotHeader.class, name = "snapshot"),
    @JsonSubTypes.Type(value = JournalLine.JournalHeader.class, name = "journal"),
    @JsonSubTypes.Type(value = JournalLine.RunLine.class, name = "run"),
    @JsonSubTypes.Type(value = JournalLine.SpanLine.class, name = "span"),
    @JsonSubTypes.Type(value = JournalLine.LogLine.class, name = "log"),
    @JsonSubTypes.Type(value = JournalLine.RequestLine.class, name = "request"),
    @JsonSubTypes.Type(value = JournalLine.DiscardLine.class, name = "discard")
})
sealed interface JournalLine {

    /** The trace the line belongs to; null for a header, a run line and a line written outside any trace. */
    @Nullable
    default String traceId() {
        return null;
    }

    /** The snapshot's first line. {@code absorbedJournal} names the journal folded into it, which a restore skips. */
    record SnapshotHeader(int version, @Nullable String absorbedJournal) implements JournalLine {}

    /** A journal's first line. A snapshot names {@code id} once it has absorbed the journal. */
    record JournalHeader(int version, String id) implements JournalLine {
        public JournalHeader {
            requireNonNull(id, "id");
        }
    }

    /** The lines below, up to the next run line, belong to the run that reported ready at {@code startedAt}. */
    record RunLine(@Nullable Instant startedAt) implements JournalLine {}

    record SpanLine(SpanData span) implements JournalLine {
        public SpanLine {
            requireNonNull(span, "span");
            requireNonNull(span.traceId(), "span.traceId");
            requireNonNull(span.spanId(), "span.spanId");
            requireNonNull(span.name(), "span.name");
        }

        @Override
        public String traceId() {
            return span.traceId();
        }
    }

    /** Field by field: the store numbers a line again on replay, so its {@code sequence} is not written. */
    record LogLine(
            @Nullable String traceId,
            @Nullable String spanId,
            Instant timestamp,
            String level,
            String logger,
            String thread,
            String message,
            @Nullable String stackTrace)
            implements JournalLine {

        public LogLine {
            requireNonNull(timestamp, "timestamp");
            requireNonNull(level, "level");
            requireNonNull(logger, "logger");
            requireNonNull(thread, "thread");
            requireNonNull(message, "message");
        }

        static LogLine of(LogCapturedEvent log) {
            return new LogLine(
                    log.traceId(),
                    log.spanId(),
                    log.timestamp(),
                    log.level(),
                    log.logger(),
                    log.thread(),
                    log.message(),
                    log.stackTrace());
        }

        LogCapturedEvent toEvent() {
            return new LogCapturedEvent(traceId, spanId, timestamp, level, logger, thread, message, stackTrace);
        }
    }

    record RequestLine(RequestCompletedEvent request) implements JournalLine {
        public RequestLine {
            requireNonNull(request, "request");
            requireNonNull(request.traceId(), "request.traceId");
        }

        @Override
        public String traceId() {
            return request.traceId();
        }
    }

    record DiscardLine(String traceId) implements JournalLine {
        public DiscardLine {
            requireNonNull(traceId, "traceId");
        }
    }
}
