package org.peekaboot.backend.testsupport;

import java.time.Instant;
import java.util.List;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.SubtreeView;
import org.peekaboot.backend.domain.trace.TraceTabSummary;
import org.peekaboot.backend.domain.trace.TraceTree;

/**
 * Builds already-mapped {@link TraceTree} fixtures for the stages after
 * {@code TraceTreeMapper}: trace {@code trace-1}, not yet judged, of UNKNOWN root action, named
 * after its root span, with a summary counting that one span and nothing else. Complete, without
 * orphans and taking as long as its root span unless told otherwise. The root span may be null.
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
        private final TraceTabSummary summary;
        private boolean complete = true;
        private List<SpanNode> orphans = List.of();
        private boolean truncated;
        private SubtreeView subtree;
        private Long durationMs;

        private Builder(SpanNode rootSpan) {
            this.rootSpan = rootSpan;
            this.summary = new TraceTabSummary(
                    null,
                    new TraceTabSummary.SpansSummary(rootSpan == null ? 0 : 1),
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

        public Builder complete(boolean complete) {
            this.complete = complete;
            return this;
        }

        public Builder orphans(List<SpanNode> orphans) {
            this.orphans = orphans;
            return this;
        }

        public Builder truncated(boolean truncated) {
            this.truncated = truncated;
            return this;
        }

        public Builder durationMs(long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public TraceTree build() {
            return new TraceTree(
                    traceId,
                    Instant.EPOCH,
                    durationMs != null ? durationMs : (rootSpan == null ? 0L : rootSpan.durationMs()),
                    complete,
                    rootActionType,
                    rootSpan == null ? null : rootSpan.name(),
                    rootSpan,
                    orphans,
                    summary,
                    subtree,
                    truncated);
        }
    }
}
