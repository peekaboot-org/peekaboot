package org.peekaboot.backend.mapper.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.peekaboot.backend.testsupport.SpanNodes.node;
import static org.peekaboot.backend.testsupport.TraceTrees.tree;

import io.micrometer.tracing.Span;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.SpanStatus;
import org.peekaboot.backend.domain.trace.TraceLog;
import org.peekaboot.backend.domain.trace.TraceReason;
import org.peekaboot.backend.domain.trace.TraceReason.Kind;

class TraceReasonsTest {

    private static final long THRESHOLD_MS = 1000;

    private static TraceLog errorLog(String spanId, String message) {
        return new TraceLog(
                "trace1", spanId, Instant.EPOCH, "ERROR", "TestLogger", "main", message, null, List.of(), List.of());
    }

    @Test
    void aFailedSpanNamesItsException() {
        SpanNode root = node("root")
                .status(SpanStatus.ERROR)
                .exception("java.lang.IllegalStateException", "boom")
                .build();

        assertThat(TraceReasons.of(tree(root).build(), List.of(), THRESHOLD_MS))
                .containsExactly(new TraceReason(Kind.EXCEPTION, "root", "java.lang.IllegalStateException: boom"));
    }

    @Test
    void aFailedSpanWithoutAMessageNamesItsClassAlone() {
        SpanNode root = node("root")
                .status(SpanStatus.ERROR)
                .exception("java.lang.NullPointerException", null)
                .build();

        assertThat(TraceReasons.of(tree(root).build(), List.of(), THRESHOLD_MS))
                .extracting(TraceReason::detail)
                .containsExactly("java.lang.NullPointerException");
    }

    @Test
    void aServerSpanAnswering5xxIsAServerError() {
        SpanNode root = node("root")
                .kind(Span.Kind.SERVER)
                .tags(Map.of("http.response.status_code", "503"))
                .build();

        assertThat(TraceReasons.of(tree(root).build(), List.of(), THRESHOLD_MS))
                .containsExactly(new TraceReason(Kind.SERVER_ERROR, "root", "Answered with status 503."));
    }

    @Test
    void eachErrorLogIsAReason() {
        SpanNode root = node("root").build();

        assertThat(TraceReasons.of(
                        tree(root).build(),
                        List.of(errorLog("root", "first"), errorLog("root", "second")),
                        THRESHOLD_MS))
                .extracting(TraceReason::kind, TraceReason::detail)
                .containsExactly(tuple(Kind.ERROR_LOG, "first"), tuple(Kind.ERROR_LOG, "second"));
    }

    /** At the threshold is slow, the comparison the Slow bucket admits by. */
    @Test
    void aTraceAtTheSlowTraceThresholdIsSlow() {
        SpanNode root = node("root").durationMs(THRESHOLD_MS).build();

        assertThat(TraceReasons.of(tree(root).build(), List.of(), THRESHOLD_MS))
                .containsExactly(new TraceReason(
                        Kind.SLOW, "root", "Took 1000 ms, at or above the 1000 ms slow-trace threshold."));
        assertThat(TraceReasons.of(tree(root).durationMs(THRESHOLD_MS - 1).build(), List.of(), THRESHOLD_MS))
                .isEmpty();
    }

    @Test
    void aSlowTraceWithoutItsRootNamesNoSpan() {
        SpanNode orphan = node("orphan").build();

        assertThat(TraceReasons.of(
                        tree(null)
                                .complete(false)
                                .orphans(List.of(orphan))
                                .durationMs(THRESHOLD_MS)
                                .build(),
                        List.of(),
                        THRESHOLD_MS))
                .containsExactly(new TraceReason(
                        Kind.SLOW, null, "Took 1000 ms, at or above the 1000 ms slow-trace threshold."));
    }

    /** Failures come before slowness, each kind in the order an agent reads the tree. */
    @Test
    void ordersFailuresByKindThenSlowness() {
        SpanNode child = node("child")
                .status(SpanStatus.ERROR)
                .exception("java.io.IOException", "reset")
                .build();
        SpanNode root = node("root")
                .kind(Span.Kind.SERVER)
                .tags(Map.of("status", "500"))
                .status(SpanStatus.ERROR)
                .exception("java.io.IOException", "reset")
                .durationMs(THRESHOLD_MS)
                .children(List.of(child))
                .build();

        assertThat(TraceReasons.of(tree(root).build(), List.of(errorLog("child", "logged")), THRESHOLD_MS))
                .extracting(TraceReason::kind, TraceReason::spanId)
                .containsExactly(
                        tuple(Kind.EXCEPTION, "root"),
                        tuple(Kind.EXCEPTION, "child"),
                        tuple(Kind.SERVER_ERROR, "root"),
                        tuple(Kind.ERROR_LOG, "child"),
                        tuple(Kind.SLOW, "root"));
    }
}
