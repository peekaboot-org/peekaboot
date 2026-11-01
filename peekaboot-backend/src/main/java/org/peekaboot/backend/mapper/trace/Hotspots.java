package org.peekaboot.backend.mapper.trace;

import java.util.Comparator;
import java.util.List;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.TraceSummary;
import org.peekaboot.backend.domain.trace.TraceTree;

/** The spans that did the most work themselves, where a slow trace spent its time. */
final class Hotspots {

    private static final int LIMIT = 3;

    private Hotspots() {}

    /** The sort is stable, so of equal self times the span walked first comes first. */
    static List<TraceSummary.Hotspot> of(TraceTree tree) {
        return SpanWalk.depthFirst(tree.rootSpan(), tree.orphans())
                .filter(span -> span.selfMs() > 0)
                .sorted(Comparator.comparingLong(SpanNode::selfMs).reversed())
                .limit(LIMIT)
                .map(span -> new TraceSummary.Hotspot(span.spanId(), span.name(), span.selfMs()))
                .toList();
    }
}
