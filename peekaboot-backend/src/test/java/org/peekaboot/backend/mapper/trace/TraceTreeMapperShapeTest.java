package org.peekaboot.backend.mapper.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.Spans.jdbcDuplicate;
import static org.peekaboot.backend.testsupport.Spans.jdbcQuery;
import static org.peekaboot.backend.testsupport.Spans.span;

import io.micrometer.tracing.Span;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.SpanStatus;
import org.peekaboot.backend.domain.trace.TraceTree;
import org.peekaboot.backend.testsupport.TraceDatas;
import org.peekaboot.backend.tracing.store.TraceData;

/**
 * The root, children and orphans of the tree {@link TraceTreeMapper} builds, and each span's times
 * and status.
 */
class TraceTreeMapperShapeTest extends TraceTreeMapperTestBase {

    /** child2 is created before child1 but starts after it, so only the start-time sort puts child1 first. */
    @Test
    void buildsATreeFromFlatSpans() {
        var rootSpan = span("span-root")
                .named("root-op")
                .kind(Span.Kind.SERVER)
                .at(0, 100)
                .order(1)
                .build();
        var child2 = span("span-child2")
                .parent("span-root")
                .named("child2-op")
                .kind(Span.Kind.CLIENT)
                .at(50, 30)
                .order(2)
                .build();
        var child1 = span("span-child1")
                .parent("span-root")
                .named("child1-op")
                .kind(Span.Kind.CLIENT)
                .at(10, 40)
                .order(3)
                .build();
        var grandchild = span("span-grandchild")
                .parent("span-child1")
                .named("grandchild-op")
                .kind(Span.Kind.CLIENT)
                .at(15, 20)
                .order(4)
                .build();

        TraceTree result = map(mapper, TraceDatas.of("trace1", rootSpan, child1, child2, grandchild));

        assertThat(result.traceId()).isEqualTo("trace1");
        assertThat(result.rootSpan().spanId()).isEqualTo("span-root");
        assertThat(result.rootSpan().name()).isEqualTo("root-op");
        assertThat(result.rootSpan().children())
                .extracting(SpanNode::spanId)
                .containsExactly("span-child1", "span-child2");
        assertThat(result.rootSpan().children().get(0).children())
                .extracting(SpanNode::spanId)
                .containsExactly("span-grandchild");
    }

    /** A parent's self time is what no child covers, so a request waiting on a slow query is not itself slow. */
    @Test
    void eachSpanCarriesItsSelfTime() {
        var root = span("root")
                .named("http get /orders")
                .kind(Span.Kind.SERVER)
                .at(0, 100)
                .order(1)
                .build();
        var query = span("query")
                .named("SELECT 1")
                .kind(Span.Kind.CLIENT)
                .parent("root")
                .at(20, 70)
                .order(2)
                .build();

        TraceTree result = map(mapper, TraceDatas.of("trace1", root, query));

        assertThat(result.rootSpan().selfMs()).isEqualTo(30);
        assertThat(result.rootSpan().children().getFirst().selfMs()).isEqualTo(70);
    }

    @Test
    void attachesOrphanSubtreesToRoot() {
        // Spans can arrive before their parent is exported; a subtree whose
        // parent is missing must not silently vanish from the tree.
        var root =
                span("root").named("root-op").kind(Span.Kind.SERVER).at(0, 100).build();
        var orphan = span("orphan")
                .parent("missing-parent")
                .named("orphan-op")
                .kind(Span.Kind.CLIENT)
                .at(10, 20)
                .build();
        var orphanChild = span("orphan-child")
                .parent("orphan")
                .named("orphan-child-op")
                .kind(Span.Kind.CLIENT)
                .at(12, 5)
                .build();

        var traceData = TraceDatas.of("trace1", root, orphan, orphanChild);

        TraceTree result = map(mapper, traceData);

        assertThat(result.rootSpan().spanId()).isEqualTo("root");
        assertThat(result.rootSpan().children()).extracting(SpanNode::spanId).contains("orphan");
        SpanNode orphanNode = result.rootSpan().children().stream()
                .filter(c -> c.spanId().equals("orphan"))
                .findFirst()
                .orElseThrow();
        assertThat(orphanNode.children()).extracting(SpanNode::spanId).containsExactly("orphan-child");
        assertThat(result.complete()).isTrue();
        assertThat(result.orphans()).isEmpty();
    }

    /** Two parentless spans: the earlier-created one is the root and the other hangs under it. */
    @Test
    void theEarlierOfTwoParentlessSpansIsTheRootAndTheOtherItsChild() {
        var first = span("first").named("first-op").at(0, 100).order(1).build();
        var second = span("second").named("second-op").at(50, 20).order(2).build();

        TraceTree result = map(mapper, TraceDatas.of("trace1", second, first));

        assertThat(result.rootSpan().spanId()).isEqualTo("first");
        assertThat(result.rootSpan().children()).extracting(SpanNode::spanId).containsExactly("second");
    }

    /** A cycle has no span without a parent and no span whose parent is missing; its spans are still shown. */
    @Test
    void aTraceThatIsOnlyACycleStillShowsEverySpan() {
        // the three spans of TraceDataBundleTest.aTraceThatIsOnlyACycleHasNoRoot
        TraceTree result = map(
                mapper,
                TraceDatas.of(
                        "trace1",
                        jdbcQuery("a", "SELECT 1").parent("dup").order(1).build(),
                        jdbcQuery("b", "SELECT 2").parent("a").order(2).build(),
                        jdbcDuplicate("dup", "b", "SELECT 2").order(3).build()));

        assertThat(result.orphans()).hasSize(1);
        List<String> shown = new ArrayList<>();
        collectIds(result.orphans().getFirst(), shown);
        assertThat(shown).containsExactlyInAnyOrder("a", "b");
        assertThat(result.spanCount()).isEqualTo(2);
    }

    /** Each stored subtree of a trace whose root has not arrived is served on its own, earliest start first. */
    @Test
    void mapsAnInFlightTraceAsItsStoredSubtrees() {
        var later = span("later")
                .named("PersonService.find")
                .parent("gone-1")
                .at(20, 10)
                .order(1)
                .build();
        var laterChild = span("later-child")
                .named("SELECT person")
                .kind(Span.Kind.CLIENT)
                .parent("later")
                .at(21, 5)
                .order(2)
                .build();
        var earlier = span("earlier")
                .named("AuditService.record")
                .parent("gone-2")
                .at(10, 5)
                .order(3)
                .build();

        TraceTree result = map(mapper, TraceDatas.of("trace1", later, laterChild, earlier));

        assertThat(result.rootSpan()).isNull();
        assertThat(result.complete()).isFalse();
        assertThat(result.orphans()).extracting(SpanNode::spanId).containsExactly("earlier", "later");
        assertThat(result.orphans().get(1).children())
                .extracting(SpanNode::spanId)
                .containsExactly("later-child");
        assertThat(result.rootActionType()).isEqualTo(RootActionType.UNKNOWN);
    }

    /** The span cap evicted the root of a request that ended: it stays complete and shows what is left. */
    @Test
    void aCompleteTraceWhoseRootWasEvictedServesEveryStoredSpanAsOrphans() {
        var lateOne = span("late-1")
                .named("late work one")
                .parent("root")
                .at(10, 5)
                .order(3)
                .build();
        var lateTwo = span("late-2")
                .named("late work two")
                .parent("root")
                .at(20, 5)
                .order(4)
                .build();
        var rootEvicted = new TraceData(
                "trace1",
                null,
                Instant.EPOCH,
                Duration.ofMillis(100),
                null,
                true,
                false,
                false,
                List.of(lateOne, lateTwo),
                Set.of(),
                1);

        TraceTree result = map(mapper, rootEvicted);

        assertThat(result.complete()).isTrue();
        assertThat(result.rootSpan()).isNull();
        assertThat(result.orphans()).extracting(SpanNode::spanId).containsExactly("late-1", "late-2");
    }

    @Test
    void identifiesRootSpanWithNullParentId() {
        var rootSpan = span("root-id")
                .named("root-op")
                .kind(Span.Kind.SERVER)
                .at(0, 100)
                .build();
        var childSpan = span("child-id")
                .parent("root-id")
                .named("child-op")
                .kind(Span.Kind.CLIENT)
                .at(10, 50)
                .build();

        var traceData = TraceDatas.of("trace1", rootSpan, childSpan);

        TraceTree result = map(mapper, traceData);

        assertThat(result.rootSpan()).isNotNull();
        assertThat(result.rootSpan().spanId()).isEqualTo("root-id");
        assertThat(result.rootOperation()).isEqualTo("root-op");
    }

    @Test
    void aSpanThatThrewIsMarkedError() {
        var root =
                span("root").named("root-op").kind(Span.Kind.SERVER).at(0, 100).build();
        var errorSpan = span("error")
                .parent("root")
                .named("error-op")
                .kind(Span.Kind.CLIENT)
                .at(10, 50)
                .exception("java.net.ConnectException", "Connection refused")
                .build();

        var traceData = TraceDatas.of("trace1", root, errorSpan);

        TraceTree result = map(mapper, traceData);

        assertThat(result.rootSpan().children()).singleElement().satisfies(failed -> {
            assertThat(failed.spanId()).isEqualTo("error");
            assertThat(failed.status()).isEqualTo(SpanStatus.ERROR);
        });
    }

    @Test
    void mapsAnEmptyTraceToARootlessTree() {
        var traceData = new TraceData("trace1", null, null, null, null, false, false, false, List.of(), Set.of(), 0);

        TraceTree result = map(mapper, traceData);

        assertThat(result.traceId()).isEqualTo("trace1");
        assertThat(result.rootSpan()).isNull();
        assertThat(result.spanCount()).isEqualTo(0);
        assertThat(result.complete()).isFalse();
        // No root span means nothing to classify from - not a guess at the commonest type.
        assertThat(result.rootActionType()).isEqualTo(RootActionType.UNKNOWN);
    }

    /** A replayed trace holds the window its remnant carried before any span, and the listing reads that window too. */
    @Test
    void anEmptyTraceKeepsTheWindowTheStoreHolds() {
        var traceData = new TraceData(
                "trace1", null, Instant.EPOCH, Duration.ofMillis(40), null, true, false, false, List.of(), Set.of(), 0);

        TraceTree result = map(mapper, traceData);

        assertThat(result.startTime()).isEqualTo(Instant.EPOCH);
        assertThat(result.durationMs()).isEqualTo(40);
        assertThat(result.rootSpan()).isNull();
        assertThat(result.orphans()).isEmpty();
    }

    /**
     * TraceData is a public record and map() is public API, so a caller can hand over a root
     * that is not one of the spans; TraceDataBundle.snapshot() is only one of its sources.
     */
    @Test
    void mapsATraceWhoseRootIsNotAmongItsSpans() {
        var listed = span("span-child")
                .parent("span-root")
                .named("child-op")
                .at(10, 40)
                .build();
        var detachedRoot = span("span-root")
                .parent("span-caller")
                .named("root-op")
                .kind(Span.Kind.SERVER)
                .at(0, 100)
                .build();
        var traceData = new TraceData(
                "trace1",
                null,
                Instant.EPOCH,
                Duration.ofMillis(100),
                detachedRoot,
                true,
                false,
                false,
                List.of(listed),
                Set.of(),
                0);

        TraceTree result = map(mapper, traceData);

        assertThat(result.rootSpan().spanId()).isEqualTo("span-root");
        assertThat(result.rootSpan().children()).extracting(SpanNode::spanId).containsExactly("span-child");
    }

    @Test
    void mapsASingleSpanTrace() {
        var singleSpan = span("only-span")
                .named("single-op")
                .kind(Span.Kind.SERVER)
                .at(0, 100)
                .tags(Map.of("service.name", "api"))
                .build();

        var traceData = TraceDatas.of("trace1", singleSpan);

        TraceTree result = map(mapper, traceData);

        assertThat(result.rootSpan()).isNotNull();
        assertThat(result.rootSpan().spanId()).isEqualTo("only-span");
        assertThat(result.rootSpan().children()).isEmpty();
        assertThat(result.spanCount()).isEqualTo(1);
        assertThat(result.rootOperation()).isEqualTo("single-op");
    }

    @Test
    void servesStartsAsInstantsAndDurationsInMilliseconds() {
        var baseTime = Instant.parse("2024-01-15T10:00:00.123456789Z");
        var span = span("span1")
                .kind(Span.Kind.SERVER)
                .at(baseTime, Duration.ofMillis(150))
                .build();

        var traceData = TraceDatas.of("trace1", span);

        TraceTree result = map(mapper, traceData);

        assertThat(result.startTime()).isEqualTo(baseTime);
        assertThat(result.durationMs()).isEqualTo(150L);
        assertThat(result.rootSpan().startTime()).isEqualTo(baseTime);
        assertThat(result.rootSpan().durationMs()).isEqualTo(150L);
    }

    @Test
    void setsSpanStatusBasedOnError() {
        var okSpan = span("ok").named("ok-op").kind(Span.Kind.SERVER).at(0, 100).build();
        var errorChild = span("error")
                .parent("ok")
                .named("error-op")
                .kind(Span.Kind.CLIENT)
                .at(10, 50)
                .exception("Exception", "Error")
                .build();

        var traceData = TraceDatas.of("trace1", okSpan, errorChild);

        TraceTree result = map(mapper, traceData);

        assertThat(result.rootSpan().status()).isEqualTo(SpanStatus.OK);
        assertThat(result.rootSpan().children().get(0).status()).isEqualTo(SpanStatus.ERROR);
    }

    /** A span whose local parent has not arrived is a piece of a request still running, not its root. */
    @Test
    void aLoneSpanWithAMissingParentRootsNothing() {
        var orphan = span("orphan")
                .parent("missing-parent")
                .named("orphan-op")
                .kind(Span.Kind.CLIENT)
                .at(0, 50)
                .build();

        TraceTree result = map(mapper, TraceDatas.of("trace1", orphan));

        assertThat(result.rootSpan()).isNull();
        assertThat(result.rootActionType()).isEqualTo(RootActionType.UNKNOWN);
    }

    private static void collectIds(SpanNode node, List<String> ids) {
        ids.add(node.spanId());
        node.children().forEach(child -> collectIds(child, ids));
    }
}
