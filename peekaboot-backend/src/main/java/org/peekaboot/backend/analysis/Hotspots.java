package org.peekaboot.backend.analysis;

import java.util.Comparator;
import java.util.List;
import org.peekaboot.backend.analysis.SpanWalk.Visit;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.TraceSummary;

/** The spans that did the most work themselves, where a slow trace spent its time. */
public final class Hotspots {

    private static final int LIMIT = 3;

    private Hotspots() {}

    /** The sort is stable, so of equal self times the span walked first comes first. */
    public static List<TraceSummary.Hotspot> of(List<Visit> visits) {
        return visits.stream()
                .map(Visit::span)
                .filter(span -> span.selfMs() > 0)
                .sorted(Comparator.comparingLong(SpanNode::selfMs).reversed())
                .limit(LIMIT)
                .map(span -> new TraceSummary.Hotspot(span.spanId(), span.name(), span.selfMs()))
                .toList();
    }
}
