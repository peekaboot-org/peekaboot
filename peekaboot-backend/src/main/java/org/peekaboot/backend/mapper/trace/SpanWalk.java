package org.peekaboot.backend.mapper.trace;

import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.trace.SpanNode;

/** A mapped trace's spans in the order the Spans tab draws them, each before its children. */
final class SpanWalk {

    private SpanWalk() {}

    /** The root's tree, or while the root is missing each orphan subtree in turn. */
    static Stream<SpanNode> depthFirst(@Nullable SpanNode rootSpan, List<SpanNode> orphans) {
        Stream<SpanNode> tops = rootSpan != null ? Stream.of(rootSpan) : orphans.stream();
        return tops.flatMap(SpanWalk::subtree);
    }

    static Stream<SpanNode> subtree(SpanNode span) {
        return Stream.concat(Stream.of(span), span.children().stream().flatMap(SpanWalk::subtree));
    }
}
