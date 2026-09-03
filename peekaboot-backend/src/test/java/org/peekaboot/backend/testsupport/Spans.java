package org.peekaboot.backend.testsupport;

import io.micrometer.tracing.Span;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.peekaboot.backend.tracing.store.SpanData;

/**
 * Builds {@link SpanData} fixtures. Every field has a neutral default - trace {@code trace1},
 * no parent, no kind, starting at the epoch with zero duration, no tags, no error, and a
 * creation order that increases per builder created - so a test names only what it
 * asserts on.
 */
public final class Spans {

    public static final String DEFAULT_TRACE_ID = "trace1";

    private static final AtomicLong NEXT_CREATION_ORDER = new AtomicLong();

    private Spans() {}

    public static SpanBuilder span(String spanId) {
        return new SpanBuilder(spanId);
    }

    /**
     * A datasource-proxy query span: CLIENT kind, named {@code query}, carrying the SQL under
     * {@code jdbc.query[0]} and the real datasource name under {@code peer.service}.
     */
    public static SpanBuilder jdbcQuery(String spanId, String sql) {
        return span(spanId)
                .named("query")
                .kind(Span.Kind.CLIENT)
                .tag("jdbc.query[0]", sql)
                .tag("peer.service", "sample_app_db");
    }

    /**
     * The double-instrumented twin of {@link #jdbcQuery}: a direct child of the real span,
     * identical except that {@code peer.service} carries the proxy's generic bean name.
     */
    public static SpanBuilder jdbcDuplicate(String spanId, String realSpanId, String sql) {
        return jdbcQuery(spanId, sql).parent(realSpanId).tag("peer.service", "dataSource");
    }

    public static final class SpanBuilder {

        private final String spanId;
        private String traceId = DEFAULT_TRACE_ID;
        private String parentId;
        private String name = "op";
        private Span.Kind kind;
        private Instant startTime = Instant.EPOCH;
        private Duration duration = Duration.ZERO;
        private final Map<String, String> tags = new LinkedHashMap<>();
        private List<SpanData.Event> events = List.of();
        private String errorMessage;
        private String errorClass;
        private String remoteServiceName;
        private long creationOrder = NEXT_CREATION_ORDER.incrementAndGet();

        private SpanBuilder(String spanId) {
            this.spanId = spanId;
        }

        public SpanBuilder in(String traceId) {
            this.traceId = traceId;
            return this;
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

        public SpanBuilder at(Instant startTime, Duration duration) {
            this.startTime = startTime;
            this.duration = duration;
            return this;
        }

        /** Epoch-relative timing: starts {@code startOffsetMs} after the epoch and lasts {@code durationMs}. */
        public SpanBuilder at(long startOffsetMs, long durationMs) {
            return at(Instant.EPOCH.plusMillis(startOffsetMs), Duration.ofMillis(durationMs));
        }

        /** Replaces every tag set so far. */
        public SpanBuilder tags(Map<String, String> tags) {
            this.tags.clear();
            this.tags.putAll(tags);
            return this;
        }

        public SpanBuilder tag(String key, String value) {
            tags.put(key, value);
            return this;
        }

        public SpanBuilder events(List<SpanData.Event> events) {
            this.events = events;
            return this;
        }

        public SpanBuilder error(String errorMessage, String errorClass) {
            this.errorMessage = errorMessage;
            this.errorClass = errorClass;
            return this;
        }

        public SpanBuilder remoteServiceName(String remoteServiceName) {
            this.remoteServiceName = remoteServiceName;
            return this;
        }

        public SpanBuilder order(long creationOrder) {
            this.creationOrder = creationOrder;
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
                    events,
                    errorMessage,
                    errorClass,
                    remoteServiceName,
                    creationOrder);
        }
    }
}
