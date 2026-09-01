package org.peekaboot.backend.testsupport;

import java.util.List;
import java.util.Map;
import org.peekaboot.backend.domain.trace.SpanEvent;
import org.peekaboot.backend.domain.trace.SpanIssue;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.SpanStatus;
import org.peekaboot.backend.domain.trace.TraceLog;

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
        private String kind = "SERVER";
        private long startTimeMs;
        private long durationMs;
        private SpanStatus status = SpanStatus.OK;
        private List<SpanNode> children = List.of();
        private Map<String, Object> tags = Map.of();
        private List<SpanEvent> events = List.of();
        private List<SpanIssue> issues = List.of();
        private long creationOrder;
        private String errorMessage;
        private String errorClass;
        private String remoteServiceName;
        private String query;
        private List<TraceLog> logs;

        private Builder(String spanId) {
            this.spanId = spanId;
        }

        public Builder named(String name) {
            this.name = name;
            return this;
        }

        public Builder kind(String kind) {
            this.kind = kind;
            return this;
        }

        public Builder startTimeMs(long startTimeMs) {
            this.startTimeMs = startTimeMs;
            return this;
        }

        public Builder durationMs(long durationMs) {
            this.durationMs = durationMs;
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

        public Builder tags(Map<String, Object> tags) {
            this.tags = tags;
            return this;
        }

        public Builder issues(List<SpanIssue> issues) {
            this.issues = issues;
            return this;
        }

        public Builder order(long creationOrder) {
            this.creationOrder = creationOrder;
            return this;
        }

        public Builder error(String errorMessage, String errorClass) {
            this.errorMessage = errorMessage;
            this.errorClass = errorClass;
            return this;
        }

        public Builder remoteServiceName(String remoteServiceName) {
            this.remoteServiceName = remoteServiceName;
            return this;
        }

        public Builder query(String query) {
            this.query = query;
            return this;
        }

        public Builder logs(List<TraceLog> logs) {
            this.logs = logs;
            return this;
        }

        public SpanNode build() {
            return new SpanNode(
                    spanId,
                    name,
                    kind,
                    startTimeMs,
                    durationMs,
                    status,
                    children,
                    tags,
                    events,
                    issues,
                    creationOrder,
                    errorMessage,
                    errorClass,
                    remoteServiceName,
                    query,
                    logs);
        }
    }
}
