package org.peekaboot.backend.mapper.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.Spans.asyncTask;
import static org.peekaboot.backend.testsupport.Spans.jdbcQuery;
import static org.peekaboot.backend.testsupport.Spans.span;

import io.micrometer.tracing.Span;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.SubtreeView;
import org.peekaboot.backend.domain.trace.TraceTree;
import org.peekaboot.backend.stacktrace.StackTraceRules;
import org.peekaboot.backend.testsupport.TraceDatas;
import org.peekaboot.backend.tracing.tags.AsyncTaskMarker;

/**
 * The tree {@link TraceTreeMapper#mapSubtree} builds for one async subtree, and the async entries a
 * whole tree marks.
 */
class TraceTreeMapperSubtreeTest extends TraceTreeMapperTestBase {

    @Test
    void mapsAnAsyncSubtreeAsATreeOfItsOwn() {
        var root = span("root").kind(Span.Kind.SERVER).at(0, 50).build();
        var async = asyncTask("async").parent("root").at(40, 240_000).build();
        var deep = span("deep").parent("async").at(100, 200_000).build();

        var traceData = TraceDatas.of("trace1", root, async, deep);

        TraceTree result = mapSubtree(mapper, traceData, "async");

        assertThat(result.subtree()).isEqualTo(new SubtreeView("async", true));
        assertThat(result.rootSpan().spanId()).isEqualTo("async");
        assertThat(result.rootActionType()).isEqualTo(RootActionType.ASYNC_TASK);
        assertThat(result.rootOperation()).isEqualTo(AsyncTaskMarker.CONTEXTUAL_NAME);
        assertThat(result.startTime()).isEqualTo(Instant.EPOCH.plusMillis(40));
        assertThat(result.durationMs()).isEqualTo(240_000);
    }

    /**
     * A child outliving its parent is the ordering the whole feature exists for, so the
     * subtree's window cannot be the entry span's own duration.
     */
    @Test
    void theSubtreeWindowCoversAChildThatOutlivesItsParent() {
        var root =
                span("root").named("root-op").kind(Span.Kind.SERVER).at(0, 50).build();
        var async = asyncTask("async").parent("root").at(40, 1_000).build();
        var deep =
                span("deep").named("deep-op").parent("async").at(100, 240_000).build();

        var traceData = TraceDatas.of("trace1", root, async, deep);

        TraceTree result = mapSubtree(mapper, traceData, "async");

        assertThat(result.startTime()).isEqualTo(Instant.EPOCH.plusMillis(40));
        assertThat(result.durationMs()).isEqualTo(240_060);
    }

    /**
     * A row rooted at an async entry reports what that entry point's own work did, not what
     * the enclosing trace's spans add up to.
     */
    @Test
    void mapSubtreeSummarisesTheSubtreeNotTheEnclosingTrace() {
        var root =
                span("root").named("root-op").kind(Span.Kind.SERVER).at(0, 50).build();
        var async = asyncTask("async").parent("root").at(40, 1_000).build();
        var deep = span("deep").named("deep-op").parent("async").at(50, 900).build();

        var traceData = TraceDatas.of("trace1", root, async, deep);

        TraceTree result = mapSubtree(mapper, traceData, "async");

        assertThat(result.spanCount()).isEqualTo(2);
    }

    /** An orphan has no parent in the trace, so there is no enclosing trace to link back to. */
    @Test
    void anOrphanedAsyncSubtreeIsNotEnclosedByAStoredTrace() {
        var async = asyncTask("async").parent("evicted").at(0, 3_700).build();

        var traceData = TraceDatas.of("trace1", async);

        TraceTree result = mapSubtree(mapper, traceData, "async");

        assertThat(result.subtree()).isEqualTo(new SubtreeView("async", false));
    }

    @Test
    void aWholeTraceMappingCarriesNoSubtreeView() {
        var root = span("root").kind(Span.Kind.SERVER).at(0, 50).build();

        var traceData = TraceDatas.of("trace1", root);

        assertThat(map(mapper, traceData).subtree()).isNull();
    }

    /** A listing page maps one subtree per async row, so the spans outside it must cost nothing. */
    @Test
    void mapSubtreeBuildsOnlyTheSubtreesOwnSpans() {
        List<String> formatted = new ArrayList<>();
        SqlFormatter recording = sql -> {
            formatted.add(sql);
            return sql;
        };
        var root =
                span("root").named("root-op").kind(Span.Kind.SERVER).at(0, 50).build();
        var requestQuery =
                jdbcQuery("q-request", "select 1").parent("root").at(10, 5).build();
        var async = asyncTask("async").parent("root").at(40, 1_000).build();
        var taskQuery =
                jdbcQuery("q-task", "select 2").parent("async").at(50, 5).build();

        mapSubtree(
                mapperWith(recording, new StackTraceRules(List.of(), List.of())),
                TraceDatas.of("trace1", root, requestQuery, async, taskQuery),
                "async");

        assertThat(formatted).containsExactly("select 2");
    }

    /** The subtree's own parent edge is the one way back into it, so cutting it ends a cycle. */
    @Test
    void mapSubtreeOfASpanInsideACycleTerminates() {
        var a = span("a").parent("b").named("a-op").at(0, 100).order(1).build();
        var b = span("b").parent("a").named("b-op").at(10, 50).order(2).build();

        TraceTree result = mapSubtree(mapper, TraceDatas.of("trace1", a, b), "b");

        assertThat(result.rootSpan().spanId()).isEqualTo("b");
    }

    /** The cap can evict an async row's entry span between listing and mapping. */
    @Test
    void mapSubtreeOfASpanNoLongerStoredMapsTheWholeTrace() {
        var root = span("root").kind(Span.Kind.SERVER).at(0, 50).build();

        TraceTree result = mapSubtree(mapper, TraceDatas.of("trace1", root), "evicted");

        assertThat(result.subtree()).isNull();
        assertThat(result.rootSpan().spanId()).isEqualTo("root");
    }

    @Test
    void marksAnAsyncEntrySpanInTheTree() {
        var root = span("root").kind(Span.Kind.SERVER).at(0, 50).build();
        var async = asyncTask("async").parent("root").at(40, 1_000).build();
        var deep = span("deep").parent("async").at(50, 900).build();

        var traceData = TraceDatas.of("trace1", root, async, deep);

        SpanNode rootNode = map(mapper, traceData).rootSpan();

        assertThat(rootNode.asyncEntry()).isFalse();
        assertThat(rootNode.children()).singleElement().satisfies(entry -> {
            assertThat(entry.asyncEntry()).isTrue();
            assertThat(entry.children())
                    .singleElement()
                    .satisfies(child -> assertThat(child.asyncEntry()).isFalse());
        });
    }
}
