package org.peekaboot.backend.mapper.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.peekaboot.backend.testsupport.Logs.log;
import static org.peekaboot.backend.testsupport.Spans.asyncTask;
import static org.peekaboot.backend.testsupport.Spans.span;

import io.micrometer.tracing.Span;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.trace.TraceOutcome;
import org.peekaboot.backend.domain.trace.TraceReason;
import org.peekaboot.backend.domain.trace.TraceTree;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.stacktrace.StackTraceRules;
import org.peekaboot.backend.testsupport.TraceStores;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.peekaboot.backend.tracing.store.TraceBucket;

class TraceResourcesTest {

    private final TraceResources resources = new TraceResources(
            new MaskingEngine(),
            SqlFormatter.NONE,
            new PeekabootTracingProperties(),
            new StackTraceRules(List.of(), List.of()));

    private InMemoryTraceStore store = TraceStores.withDefaults();

    private TraceTree whole(String traceId) {
        return resources.row(store.getTrace(traceId).orElseThrow(), null, store.bucketsOf(traceId));
    }

    private TraceTree subtree(String traceId, String subtreeRootSpanId) {
        return resources.row(store.getTrace(traceId).orElseThrow(), subtreeRootSpanId, store.bucketsOf(traceId));
    }

    @Test
    void aFailedSpanFailsTheTrace() {
        store.addSpan(span("root")
                .in("t1")
                .kind(Span.Kind.SERVER)
                .at(0, 10)
                .exception("java.lang.IllegalStateException", "boom")
                .build());

        TraceTree trace = whole("t1");

        assertThat(trace.outcome()).isEqualTo(TraceOutcome.ERROR);
        assertThat(trace.reasons()).extracting(TraceReason::kind).containsExactly(TraceReason.Kind.EXCEPTION);
    }

    @Test
    void anErrorLogAloneFailsTheTrace() {
        store.addLog(
                log("t1").inSpan("root").at("ERROR").saying("payment declined").build());
        store.addSpan(span("root").in("t1").kind(Span.Kind.SERVER).at(0, 10).build());

        TraceTree trace = whole("t1");

        assertThat(trace.outcome()).isEqualTo(TraceOutcome.ERROR);
        assertThat(trace.buckets()).containsExactly(TraceBucket.ERRORS);
        assertThat(trace.reasons()).extracting(TraceReason::kind).containsExactly(TraceReason.Kind.ERROR_LOG);
    }

    /** Errors keeps the trace after the cap evicts the failing span, and the outcome must not contradict it. */
    @Test
    void aFailureTheSpanCapEvictedStillFailsTheTrace() {
        store = TraceStores.with(properties -> properties.setMaxSpansPerTrace(2));
        store.addSpan(span("failed")
                .in("t1")
                .parent("root")
                .at(0, 1)
                .exception("java.lang.IllegalStateException", "boom")
                .order(1)
                .build());
        store.addSpan(span("a").in("t1").parent("root").at(1, 1).order(2).build());
        store.addSpan(span("b").in("t1").parent("root").at(2, 1).order(3).build());
        store.addSpan(
                span("root").in("t1").kind(Span.Kind.SERVER).at(0, 5).order(4).build());

        TraceTree trace = whole("t1");

        assertThat(trace.reasons()).extracting(TraceReason::kind).doesNotContain(TraceReason.Kind.EXCEPTION);
        assertThat(trace.outcome()).isEqualTo(TraceOutcome.ERROR);
        assertThat(trace.buckets()).containsExactly(TraceBucket.ERRORS);
    }

    /** Background work the caller never waited for still failed inside this trace. */
    @Test
    void aFailedAsyncTaskFailsTheWholeTrace() {
        store.addSpan(asyncTask("task")
                .in("t1")
                .parent("root")
                .at(40, 1_000)
                .exception("java.lang.RuntimeException", "boom")
                .order(1)
                .build());
        store.addSpan(
                span("root").in("t1").kind(Span.Kind.SERVER).at(0, 50).order(2).build());

        assertThat(whole("t1").outcome()).isEqualTo(TraceOutcome.ERROR);
    }

    /** Background work that succeeded must not wear the ERROR of the request that dispatched it. */
    @Test
    void aSubtreeRowReportsItsOwnFailuresOnly() {
        store.addSpan(
                asyncTask("task").in("t1").parent("root").at(2, 50).order(1).build());
        store.addSpan(span("root")
                .in("t1")
                .kind(Span.Kind.SERVER)
                .at(0, 10)
                .exception("java.lang.IllegalStateException", "boom")
                .order(2)
                .build());

        TraceTree task = subtree("t1", "task");

        assertThat(task.outcome()).isEqualTo(TraceOutcome.OK);
        assertThat(task.reasons()).isEmpty();
        assertThat(task.buckets()).containsExactly(TraceBucket.ERRORS);
        assertThat(whole("t1").outcome()).isEqualTo(TraceOutcome.ERROR);
    }

    @Test
    void aSubtreeRowFailsOnAFailureDeepInsideIt() {
        store.addSpan(
                span("root").in("t1").kind(Span.Kind.SERVER).at(0, 50).order(1).build());
        store.addSpan(
                asyncTask("failed").in("t1").parent("root").at(40, 100).order(2).build());
        store.addSpan(span("deep")
                .in("t1")
                .parent("failed")
                .at(50, 50)
                .exception("java.lang.RuntimeException", "boom")
                .order(3)
                .build());
        store.addSpan(
                asyncTask("clean").in("t1").parent("root").at(40, 100).order(4).build());

        TraceTree failed = subtree("t1", "failed");

        assertThat(failed.outcome()).isEqualTo(TraceOutcome.ERROR);
        assertThat(failed.reasons())
                .extracting(TraceReason::kind, TraceReason::spanId)
                .containsExactly(tuple(TraceReason.Kind.EXCEPTION, "deep"));
        assertThat(subtree("t1", "clean").outcome()).isEqualTo(TraceOutcome.OK);
    }

    /** The detail reads its ERROR logs off the list it serves, whatever the level's case. */
    @Test
    void theDetailNamesEachErrorLogAmongItsLogs() {
        store.addLog(log("t1").inSpan("root").at("INFO").saying("charging card").build());
        store.addLog(
                log("t1").inSpan("root").at("error").saying("payment declined").build());
        store.addLog(log("t1").inSpan("root").at("WARN").saying("retrying").build());
        store.addSpan(span("root").in("t1").kind(Span.Kind.SERVER).at(0, 10).build());

        TraceTree trace = resources.trace(store.getTrace("t1").orElseThrow(), store.bucketsOf("t1"));

        assertThat(trace.logs()).hasSize(3);
        assertThat(trace.reasons())
                .extracting(TraceReason::kind, TraceReason::spanId, TraceReason::detail)
                .containsExactly(tuple(TraceReason.Kind.ERROR_LOG, "root", "payment declined"));
        assertThat(trace.outcome()).isEqualTo(TraceOutcome.ERROR);
    }

    @Test
    void aTraceStillRunningIsInProgressWhateverFailed() {
        store.addSpan(span("child")
                .in("t1")
                .parent("root")
                .at(0, 1)
                .exception("java.lang.IllegalStateException", "boom")
                .build());

        TraceTree trace = resources.trace(store.getTrace("t1").orElseThrow(), store.bucketsOf("t1"));

        assertThat(trace.outcome()).isEqualTo(TraceOutcome.IN_PROGRESS);
        assertThat(trace.reasons()).extracting(TraceReason::kind).containsExactly(TraceReason.Kind.EXCEPTION);
    }
}
