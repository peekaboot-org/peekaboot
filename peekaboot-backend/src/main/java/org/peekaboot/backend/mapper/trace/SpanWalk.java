package org.peekaboot.backend.mapper.trace;

import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.trace.SpanNode;

/** A mapped trace's spans in the order the Spans tab draws them, each before its children. */
final class SpanWalk {

    /** @param depth 0 for a top span, and one more for each generation below it */
    record Visit(SpanNode span, int depth) {}

    private SpanWalk() {}

    /** The root's tree, or while the root is missing each orphan subtree in turn. */
    static Stream<SpanNode> depthFirst(@Nullable SpanNode rootSpan, List<SpanNode> orphans) {
        return visits(rootSpan, orphans).map(Visit::span);
    }

    /** {@link #depthFirst} with each span's depth. */
    static Stream<Visit> visits(@Nullable SpanNode rootSpan, List<SpanNode> orphans) {
        Stream<SpanNode> tops = rootSpan != null ? Stream.of(rootSpan) : orphans.stream();
        return tops.flatMap(top -> visitsFrom(top, 0));
    }

    static Stream<SpanNode> subtree(SpanNode span) {
        return visitsFrom(span, 0).map(Visit::span);
    }

    private static Stream<Visit> visitsFrom(SpanNode span, int depth) {
        return Stream.concat(
                Stream.of(new Visit(span, depth)),
                span.children().stream().flatMap(child -> visitsFrom(child, depth + 1)));
    }
}
