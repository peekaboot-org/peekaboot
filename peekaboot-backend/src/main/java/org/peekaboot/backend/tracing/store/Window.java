package org.peekaboot.backend.tracing.store;

import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.tracing.event.SpanData;

/**
 * The wall-clock window a set of spans covers: the earliest start and the latest end folded in
 * so far, each null until a span carrying one arrives. Immutable, so a holder replaces it on
 * every fold and a reader keeps the one it read.
 */
record Window(@Nullable Instant start, @Nullable Instant end) {

    static final Window EMPTY = new Window(null, null);

    /** This window stretched to cover {@code span}; this same instance where it already does. */
    Window including(SpanData span) {
        boolean earlierStart =
                span.startTime() != null && (start == null || span.startTime().isBefore(start));
        boolean laterEnd =
                span.endTime() != null && (end == null || span.endTime().isAfter(end));
        if (!earlierStart && !laterEnd) {
            return this;
        }
        return new Window(earlierStart ? span.startTime() : start, laterEnd ? span.endTime() : end);
    }

    /** Whether {@code span} sits on either edge: the only spans whose removal can shrink this window. */
    boolean isBoundedBy(SpanData span) {
        return (span.startTime() != null && span.startTime().equals(start))
                || (span.endTime() != null && span.endTime().equals(end));
    }

    /** Null until both a start and an end have been folded in. */
    @Nullable
    Duration duration() {
        return start == null || end == null ? null : Duration.between(start, end);
    }
}
