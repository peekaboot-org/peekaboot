package org.peekaboot.testingapp.integration;

import io.micrometer.tracing.Span;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.peekaboot.backend.tracing.store.SpanData;

/**
 * Builds {@link SpanData} for the tests that write straight to the store. Every field has a
 * neutral default - no parent, no kind, starting at the epoch with zero duration, no tags,
 * no error, and a creation order that increases per builder - so a test names only what it
 * asserts on. Same shape as peekaboot-backend's {@code Spans}, which this module cannot
 * reach (the backend publishes no test jar).
 *
 * <p>Public because the UI tests write spans too: a trace over the max-spans-per-trace cap,
 * or one carrying a span event, is a shape the sample app does not produce on request.
 */
public final class TestSpans {

    private static final AtomicLong NEXT_CREATION_ORDER = new AtomicLong();

    private TestSpans() {}

    public static SpanBuilder span(String traceId, String spanId) {
        return new SpanBuilder(traceId, spanId);
    }

    public static final class SpanBuilder {

        private final String traceId;
        private final String spanId;
        private String parentId;
        private String name = "op";
        private Span.Kind kind;
        private Instant startTime = Instant.EPOCH;
        private Duration duration = Duration.ZERO;
        private final Map<String, String> tags = new LinkedHashMap<>();
        private final List<SpanData.Event> events = new ArrayList<>();
        private String errorMessage;
        private String errorClass;

        private SpanBuilder(String traceId, String spanId) {
            this.traceId = traceId;
            this.spanId = spanId;
        }

        public SpanBuilder parent(String parentId) {
            this.parentId = parentId;
            return this;
        }

        public SpanBuilder named(String name) {
            this.name = name;
            return this;
        }

        public SpanBuilder kind(Span.Kind kind) {
            this.kind = kind;
            return this;
        }

        /** Epoch-relative timing: starts {@code startOffsetMs} after the epoch and lasts {@code durationMs}. */
        public SpanBuilder at(long startOffsetMs, long durationMs) {
            this.startTime = Instant.EPOCH.plusMillis(startOffsetMs);
            this.duration = Duration.ofMillis(durationMs);
            return this;
        }

        public SpanBuilder tag(String key, String value) {
            tags.put(key, value);
            return this;
        }

        /** An event recorded on the span, the way an instrumentation records an exception. */
        public SpanBuilder event(String name, long offsetMs) {
            events.add(new SpanData.Event(name, Instant.EPOCH.plusMillis(offsetMs)));
            return this;
        }

        public SpanBuilder error(String errorMessage, String errorClass) {
            this.errorMessage = errorMessage;
            this.errorClass = errorClass;
            return this;
        }

        public SpanData build() {
            return new SpanData(
                    traceId,
                    spanId,
                    parentId,
                    name,
                    kind,
                    startTime,
                    startTime.plus(duration),
                    duration,
                    Map.copyOf(tags),
                    List.copyOf(events),
                    errorMessage,
                    errorClass,
                    null,
                    NEXT_CREATION_ORDER.incrementAndGet());
        }
    }
}
