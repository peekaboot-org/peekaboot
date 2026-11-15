package org.peekaboot.backend.mapper.trace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.trace.SpanNode;

/** A mapped trace's spans in the order the Spans tab draws them, each before its children. */
final class SpanWalk {

    /** @param depth 0 for a top span, and one more for each generation below it */
    record Visit(SpanNode span, int depth) {}

    private SpanWalk() {}

    /** The root's tree, or while the root is missing each orphan subtree in turn. */
    static List<Visit> visits(@Nullable SpanNode rootSpan, List<SpanNode> orphans) {
        List<Visit> visits = new ArrayList<>();
        // a stack of its own, since a chain of spans can nest deeper than the thread's stack allows
        Deque<Visit> pending = new ArrayDeque<>();
        push(pending, rootSpan != null ? List.of(rootSpan) : orphans, 0);
        while (!pending.isEmpty()) {
            Visit visit = pending.pop();
            visits.add(visit);
            push(pending, visit.span().children(), visit.depth() + 1);
        }
        return Collections.unmodifiableList(visits);
    }

    static List<SpanNode> subtree(SpanNode span) {
        return visits(span, List.of()).stream().map(Visit::span).toList();
    }

    /** Last first, so the first of {@code spans} is the next one walked. */
    private static void push(Deque<Visit> pending, List<SpanNode> spans, int depth) {
        for (SpanNode span : spans.reversed()) {
            pending.push(new Visit(span, depth));
        }
    }
}
