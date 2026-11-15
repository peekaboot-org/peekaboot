package org.peekaboot.backend.mapper.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.peekaboot.backend.testsupport.SpanNodes.node;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.trace.SpanNode;

class SpanWalkTest {

    private static SpanNode parent(String spanId, SpanNode... children) {
        return node(spanId).children(List.of(children)).build();
    }

    @Test
    void visitsEachSpanBeforeItsChildrenWithItsDepth() {
        SpanNode root = parent("root", parent("a", parent("a1")), parent("b"));

        assertThat(SpanWalk.visits(root, List.of()))
                .extracting(visit -> visit.span().spanId(), SpanWalk.Visit::depth)
                .containsExactly(tuple("root", 0), tuple("a", 1), tuple("a1", 2), tuple("b", 1));
    }

    @Test
    void walksEveryOrphanSubtreeWhileTheRootIsMissing() {
        List<SpanNode> orphans = List.of(parent("first", parent("first1")), parent("second", parent("second1")));

        assertThat(SpanWalk.visits(null, orphans))
                .extracting(visit -> visit.span().spanId(), SpanWalk.Visit::depth)
                .containsExactly(tuple("first", 0), tuple("first1", 1), tuple("second", 0), tuple("second1", 1));
    }

    @Test
    void aSubtreeIsItsTopSpanAndEverySpanBelowIt() {
        SpanNode top = parent("top", parent("a", parent("a1")), parent("b"));

        assertThat(SpanWalk.subtree(top)).extracting(SpanNode::spanId).containsExactly("top", "a", "a1", "b");
    }

    @Test
    void walksAChainOfTenThousandSpansWithoutRecursing() {
        SpanNode chain = parent("link9999");
        for (int link = 9998; link >= 0; link--) {
            chain = parent("link" + link, chain);
        }

        // the size itself, since a failed hasSize would print a chain no toString can descend
        assertThat(SpanWalk.visits(chain, List.of()).size()).isEqualTo(10_000);
    }
}
