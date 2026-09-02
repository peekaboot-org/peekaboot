package org.peekaboot.backend.testsupport;

import java.util.List;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.TraceStatus;
import org.peekaboot.backend.domain.trace.TraceTabSummary;
import org.peekaboot.backend.domain.trace.TraceTree;

/**
 * Builds already-mapped {@link TraceTree} fixtures for the stages after
 * {@code TraceTreeMapper}: trace {@code trace-1}, ended OK, of UNKNOWN root action, taking
 * as long as its root span and named after it, with a summary counting that one span and
 * nothing else - unless a test says otherwise. The root span may be null.
 */
public final class TraceTrees {

    private TraceTrees() {}

    public static Builder tree(SpanNode rootSpan) {
        return new Builder(rootSpan);
    }

    public static final class Builder {

        private final SpanNode rootSpan;
        private String traceId = "trace-1";
        private RootActionType rootActionType = RootActionType.UNKNOWN;
        private TraceTabSummary summary;
        private boolean truncated;

        private Builder(SpanNode rootSpan) {
            this.rootSpan = rootSpan;
            this.summary = new TraceTabSummary(
                    null,
                    new TraceTabSummary.SpansSummary(rootSpan == null ? 0 : 1, durationMs(), 0),
                    new TraceTabSummary.QueriesSummary(0, 0L),
                    new TraceTabSummary.LogsSummary(0, 0, 0));
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder rootActionType(RootActionType rootActionType) {
            this.rootActionType = rootActionType;
            return this;
        }

        public Builder summary(TraceTabSummary summary) {
            this.summary = summary;
            return this;
        }

        public Builder truncated(boolean truncated) {
            this.truncated = truncated;
            return this;
        }

        public TraceTree build() {
            return new TraceTree(
                    traceId,
                    0L,
                    durationMs(),
                    TraceStatus.OK,
                    false,
                    rootActionType,
                    rootSpan == null ? null : rootSpan.name(),
                    rootSpan,
                    summary,
                    null,
                    List.of(),
                    List.of(),
                    truncated);
        }

        private long durationMs() {
            return rootSpan == null ? 0L : rootSpan.durationMs();
        }
    }
}
