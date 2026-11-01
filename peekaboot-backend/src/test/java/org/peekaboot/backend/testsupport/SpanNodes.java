package org.peekaboot.backend.testsupport;

import io.micrometer.tracing.Span;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.peekaboot.backend.domain.trace.SpanEvent;
import org.peekaboot.backend.domain.trace.SpanExceptionInfo;
import org.peekaboot.backend.domain.trace.SpanIssue;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.SpanStatus;
import org.peekaboot.backend.domain.trace.SqlStatement;

/**
 * Builds already-mapped {@link SpanNode} fixtures for the stages after
 * {@code TraceTreeMapper} - a SERVER span named {@code test-op} that took no time, ended OK
 * and carries nothing else, unless a test says otherwise.
 */
public final class SpanNodes {

    private SpanNodes() {}

    public static Builder node(String spanId) {
        return new Builder(spanId);
    }

    public static final class Builder {

        private final String spanId;
        private String name = "test-op";
        private Span.Kind kind = Span.Kind.SERVER;
        private Instant startTime;
        private long durationMs;
        private Long selfMs;
        private SpanStatus status = SpanStatus.OK;
        private List<SpanNode> children = List.of();
        private Map<String, String> tags = Map.of();
        private List<SpanEvent> events = List.of();
        private List<SpanIssue> issues = List.of();
        private SpanExceptionInfo exception;
        private SqlStatement statement;
        private Long rowCount;
        private boolean asyncEntry;

        private Builder(String spanId) {
            this.spanId = spanId;
        }

        public Builder named(String name) {
            this.name = name;
            return this;
        }

        public Builder kind(Span.Kind kind) {
            this.kind = kind;
            return this;
        }

        public Builder startTime(Instant startTime) {
            this.startTime = startTime;
            return this;
        }

        public Builder durationMs(long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public Builder selfMs(long selfMs) {
            this.selfMs = selfMs;
            return this;
        }

        public Builder status(SpanStatus status) {
            this.status = status;
            return this;
        }

        public Builder children(List<SpanNode> children) {
            this.children = children;
            return this;
        }

        public Builder tags(Map<String, String> tags) {
            this.tags = tags;
            return this;
        }

        public Builder events(List<SpanEvent> events) {
            this.events = events;
            return this;
        }

        public Builder issues(List<SpanIssue> issues) {
            this.issues = issues;
            return this;
        }

        public Builder exception(String type, String message) {
            this.exception = new SpanExceptionInfo(type, message, null, List.of(), List.of());
            return this;
        }

        public Builder statement(SqlStatement statement) {
            this.statement = statement;
            return this;
        }

        public Builder rowCount(Long rowCount) {
            this.rowCount = rowCount;
            return this;
        }

        public SpanNode build() {
            return new SpanNode(
                    spanId,
                    name,
                    kind,
                    startTime,
                    durationMs,
                    selfMs != null ? selfMs : durationMs,
                    status,
                    children,
                    tags,
                    events,
                    issues,
                    exception,
                    statement,
                    rowCount,
                    asyncEntry);
        }
    }
}
