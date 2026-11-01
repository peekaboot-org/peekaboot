package org.peekaboot.backend.mapper.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.SpanNodes.node;
import static org.peekaboot.backend.testsupport.TraceTrees.tree;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.TraceSummary;

class HotspotsTest {

    private static SpanNode spending(String spanId, long selfMs) {
        return node(spanId)
                .named(spanId + ".op")
                .durationMs(selfMs)
                .selfMs(selfMs)
                .build();
    }

    @Test
    void theThreeSpansWithTheMostSelfTimeLargestFirst() {
        SpanNode root = node("root")
                .selfMs(5)
                .children(List.of(spending("a", 40), spending("b", 300), spending("c", 120), spending("d", 80)))
                .build();

        assertThat(Hotspots.of(tree(root).build()))
                .containsExactly(
                        new TraceSummary.Hotspot("b", "b.op", 300),
                        new TraceSummary.Hotspot("c", "c.op", 120),
                        new TraceSummary.Hotspot("d", "d.op", 80));
    }

    /** A span that only waited on its children has nothing to show here. */
    @Test
    void leavesOutSpansWithoutSelfTime() {
        SpanNode root =
                node("root").selfMs(0).children(List.of(spending("a", 10))).build();

        assertThat(Hotspots.of(tree(root).build()))
                .extracting(TraceSummary.Hotspot::spanId)
                .containsExactly("a");
    }

    @Test
    void aTraceWithoutSpansHasNone() {
        assertThat(Hotspots.of(tree(null).complete(false).build())).isEmpty();
    }

    @Test
    void aTraceWhereNoSpanWorkedItselfHasNone() {
        SpanNode root =
                node("root").selfMs(0).children(List.of(spending("waiting", 0))).build();

        assertThat(Hotspots.of(tree(root).build())).isEmpty();
    }

    @Test
    void ofEqualSelfTimesTheFirstWalkedComesFirst() {
        SpanNode root = node("root")
                .selfMs(0)
                .children(List.of(spending("first", 50), spending("second", 50)))
                .build();

        assertThat(Hotspots.of(tree(root).build()))
                .extracting(TraceSummary.Hotspot::spanId)
                .containsExactly("first", "second");
    }

    /** The walk is depth first, so the nested span beats its shallower, alphabetically earlier rival. */
    @Test
    void aTieAtTheCapKeepsTheFirstWalked() {
        SpanNode outer = node("outer")
                .selfMs(0)
                .children(List.of(spending("nested", 50)))
                .build();
        SpanNode root = node("root")
                .selfMs(0)
                .children(List.of(outer, spending("x", 300), spending("y", 200), spending("after", 50)))
                .build();

        assertThat(Hotspots.of(tree(root).build()))
                .extracting(TraceSummary.Hotspot::spanId)
                .containsExactly("x", "y", "nested");
    }

    @Test
    void readsTheSubtreesOfAnIncompleteTrace() {
        assertThat(Hotspots.of(tree(null)
                        .complete(false)
                        .orphans(List.of(spending("orphan", 70)))
                        .build()))
                .extracting(TraceSummary.Hotspot::spanId)
                .containsExactly("orphan");
    }
}
