package org.peekaboot.backend.mapper.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.peekaboot.backend.testsupport.Logs.log;
import static org.peekaboot.backend.testsupport.Spans.asyncTask;
import static org.peekaboot.backend.testsupport.Spans.jdbcQuery;
import static org.peekaboot.backend.testsupport.Spans.span;
import static org.peekaboot.backend.tracing.store.UnstoredBundles.UNFILED;

import io.micrometer.tracing.Span;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.trace.QueryInfo;
import org.peekaboot.backend.domain.trace.TraceDetail;
import org.peekaboot.backend.domain.trace.TraceLog;
import org.peekaboot.backend.domain.trace.TraceOutcome;
import org.peekaboot.backend.domain.trace.TraceReason;
import org.peekaboot.backend.domain.trace.TraceResource;
import org.peekaboot.backend.domain.trace.TraceSummary;
import org.peekaboot.backend.domain.trace.TraceView;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.stacktrace.StackTraceRules;
import org.peekaboot.backend.testsupport.TraceStores;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.peekaboot.backend.tracing.store.TraceDataBundle;

class TraceResourcesTest {

    private final TraceResources resources = new TraceResources(
            new MaskingEngine(),
            SqlFormatter.NONE,
            new PeekabootTracingProperties(),
            new StackTraceRules(List.of(), List.of()));

    private InMemoryTraceStore store = TraceStores.withDefaults();

    private TraceSummary whole(String traceId) {
        return resources
                .row(store.getTrace(traceId).orElseThrow(), null, store.bucketsOf(traceId), TraceView.SUMMARY)
                .summary();
    }

    private TraceSummary subtree(String traceId, String subtreeRootSpanId) {
        return resources
                .row(
                        store.getTrace(traceId).orElseThrow(),
                        subtreeRootSpanId,
                        store.bucketsOf(traceId),
                        TraceView.SUMMARY)
                .summary();
    }

    @Test
    void aFailedSpanFailsTheTrace() {
        store.addSpan(span("root")
                .in("t1")
                .kind(Span.Kind.SERVER)
                .at(0, 10)
                .exception("java.lang.IllegalStateException", "boom")
                .build());

        TraceSummary trace = whole("t1");

        assertThat(trace.outcome()).isEqualTo(TraceOutcome.ERROR);
        assertThat(trace.reasons()).extracting(TraceReason::kind).containsExactly(TraceReason.Kind.EXCEPTION);
    }

    @Test
    void anErrorLogAloneFailsTheTrace() {
        store.addLog(
                log("t1").inSpan("root").at("ERROR").saying("payment declined").build());
        store.addSpan(span("root").in("t1").kind(Span.Kind.SERVER).at(0, 10).build());

        TraceSummary trace = whole("t1");

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

        TraceSummary trace = whole("t1");

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

        TraceSummary task = subtree("t1", "task");

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

        TraceSummary failed = subtree("t1", "failed");

        assertThat(failed.outcome()).isEqualTo(TraceOutcome.ERROR);
        assertThat(failed.reasons())
                .extracting(TraceReason::kind, TraceReason::spanId)
                .containsExactly(tuple(TraceReason.Kind.EXCEPTION, "deep"));
        assertThat(subtree("t1", "clean").outcome()).isEqualTo(TraceOutcome.OK);
    }

    /** The request's own wait is no work of the task's, so an async row's hotspots stay inside its subtree. */
    @Test
    void aSubtreeRowsHotspotsAreItsOwnWork() {
        store.addSpan(span("work").in("t1").parent("task").at(50, 30).order(1).build());
        store.addSpan(
                asyncTask("task").in("t1").parent("root").at(40, 100).order(2).build());
        store.addSpan(
                span("root").in("t1").kind(Span.Kind.SERVER).at(0, 500).order(3).build());

        assertThat(whole("t1").hotspots())
                .extracting(TraceSummary.Hotspot::spanId, TraceSummary.Hotspot::selfMs)
                .containsExactly(tuple("root", 400L), tuple("task", 70L), tuple("work", 30L));
        assertThat(subtree("t1", "task").hotspots())
                .extracting(TraceSummary.Hotspot::spanId, TraceSummary.Hotspot::selfMs)
                .containsExactly(tuple("task", 70L), tuple("work", 30L));
    }

    /** The detail reads its ERROR logs off the list it serves, whatever the level's case. */
    @Test
    void theDetailNamesEachErrorLogAmongItsLogs() {
        store.addLog(log("t1").inSpan("root").at("INFO").saying("charging card").build());
        store.addLog(
                log("t1").inSpan("root").at("error").saying("payment declined").build());
        store.addLog(log("t1").inSpan("root").at("WARN").saying("retrying").build());
        store.addSpan(span("root").in("t1").kind(Span.Kind.SERVER).at(0, 10).build());

        TraceResource trace = resources.trace(store.getTrace("t1").orElseThrow(), store.bucketsOf("t1"));

        assertThat(trace.data().logs()).hasSize(3);
        assertThat(trace.summary().reasons())
                .extracting(TraceReason::kind, TraceReason::spanId, TraceReason::detail)
                .containsExactly(tuple(TraceReason.Kind.ERROR_LOG, "root", "payment declined"));
        assertThat(trace.summary().outcome()).isEqualTo(TraceOutcome.ERROR);
    }

    /**
     * The redirects can move between two reads while spans arrive. One mapping serves the reasons
     * and the log list, so an ERROR_LOG reason names the span its log entry names.
     */
    @Test
    void anErrorLogsReasonNamesTheSpanItsLogEntryNames() {
        TraceDataBundle movingRedirects = new TraceDataBundle("t1", 500, 500) {
            private int reads;

            @Override
            public String resolveSpanId(String spanId) {
                return spanId + "#" + reads++;
            }
        };
        movingRedirects.addSpan(
                span("root").in("t1").kind(Span.Kind.SERVER).at(0, 10).build(), UNFILED);
        movingRedirects.addLog(
                log("t1").inSpan("root").at("ERROR").saying("payment declined").build(), UNFILED);

        TraceResource trace = resources.trace(movingRedirects, Set.of());

        assertThat(trace.summary().reasons())
                .singleElement()
                .extracting(TraceReason::spanId)
                .isEqualTo(trace.data().logs().getFirst().spanId());
    }

    /**
     * The redirects can move between two reads while spans arrive. A subtree row resolves each log's
     * span once, so a log it lists names a span inside it.
     */
    @Test
    void aSubtreeRowsLogNamesTheSpanThatPutItThere() {
        TraceDataBundle movingRedirects = new TraceDataBundle("t1", 500, 500) {
            private int reads;

            @Override
            public String resolveSpanId(String spanId) {
                return reads++ == 0 ? spanId : "root";
            }
        };
        movingRedirects.addSpan(
                span("root").in("t1").kind(Span.Kind.SERVER).at(0, 10).build(), UNFILED);
        movingRedirects.addSpan(
                asyncTask("task").in("t1").parent("root").at(2, 50).build(), UNFILED);
        movingRedirects.addLog(log("t1").inSpan("task").saying("inside").build(), UNFILED);

        TraceResource row = resources.row(movingRedirects, "task", Set.of(), TraceView.FULL);

        assertThat(row.data().logs()).extracting(TraceLog::spanId).containsExactly("task");
    }

    @Test
    void aTraceStillRunningIsInProgressWhateverFailed() {
        store.addSpan(span("child")
                .in("t1")
                .parent("root")
                .at(0, 1)
                .exception("java.lang.IllegalStateException", "boom")
                .build());

        TraceSummary trace = resources
                .trace(store.getTrace("t1").orElseThrow(), store.bucketsOf("t1"))
                .summary();

        assertThat(trace.outcome()).isEqualTo(TraceOutcome.IN_PROGRESS);
        assertThat(trace.reasons()).extracting(TraceReason::kind).containsExactly(TraceReason.Kind.EXCEPTION);
    }

    @Test
    void aSummaryRowCarriesNoData() {
        store.addSpan(span("root").in("t1").kind(Span.Kind.SERVER).at(0, 10).build());

        assertThat(resources
                        .row(store.getTrace("t1").orElseThrow(), null, Set.of(), TraceView.SUMMARY)
                        .data())
                .isNull();
        assertThat(resources
                        .row(store.getTrace("t1").orElseThrow(), null, Set.of(), TraceView.FULL)
                        .data())
                .isNotNull();
    }

    /** A subtree row's data is its own work, so it lists the task's queries and no request. */
    @Test
    void aSubtreeRowsDataIsItsOwnWork() {
        store.addSpan(
                jdbcQuery("inTask", "SELECT 2").in("t1").parent("task").order(1).build());
        store.addSpan(
                asyncTask("task").in("t1").parent("root").at(2, 50).order(2).build());
        store.addSpan(jdbcQuery("inRequest", "SELECT 1")
                .in("t1")
                .parent("root")
                .order(3)
                .build());
        store.addSpan(
                span("root").in("t1").kind(Span.Kind.SERVER).at(0, 10).order(4).build());

        TraceDetail data = resources
                .row(store.getTrace("t1").orElseThrow(), "task", Set.of(), TraceView.FULL)
                .data();

        assertThat(data.queries()).extracting(QueryInfo::spanId).containsExactly("inTask");
        assertThat(data.httpExchange()).isNull();
    }

    /** The statements outside an async row's subtree are not its to serve, so they are never formatted. */
    @Test
    void aSubtreeRowFormatsOnlyItsOwnStatements() {
        List<String> formatted = new ArrayList<>();
        TraceResources recording = new TraceResources(
                new MaskingEngine(),
                sql -> {
                    formatted.add(sql);
                    return sql;
                },
                new PeekabootTracingProperties(),
                new StackTraceRules(List.of(), List.of()));
        store.addSpan(
                jdbcQuery("inTask", "SELECT 2").in("t1").parent("task").order(1).build());
        store.addSpan(
                asyncTask("task").in("t1").parent("root").at(2, 50).order(2).build());
        store.addSpan(jdbcQuery("inRequest", "SELECT 1")
                .in("t1")
                .parent("root")
                .order(3)
                .build());
        store.addSpan(
                span("root").in("t1").kind(Span.Kind.SERVER).at(0, 10).order(4).build());

        recording.row(store.getTrace("t1").orElseThrow(), "task", Set.of(), TraceView.FULL);

        assertThat(formatted).containsExactly("SELECT 2");
    }

    /** The counts name what the caps dropped, which no other count includes. */
    @Test
    void countsWhatTheCapsDropped() {
        store = TraceStores.with(properties -> {
            properties.setMaxSpansPerTrace(1);
            properties.setMaxLogsPerTrace(1);
        });
        store.addLog(log("t1").inSpan("root").saying("first").build());
        store.addLog(log("t1").inSpan("root").saying("second").build());
        store.addSpan(span("child")
                .in("t1")
                .named("child-op")
                .parent("root")
                .at(0, 1)
                .order(1)
                .build());
        store.addSpan(
                span("root").in("t1").kind(Span.Kind.SERVER).at(0, 10).order(2).build());

        TraceSummary.Counts counts = whole("t1").counts();

        assertThat(counts.spans()).isEqualTo(1);
        assertThat(counts.droppedSpans()).isEqualTo(1);
        assertThat(counts.logs().count()).isEqualTo(1);
        assertThat(counts.droppedLogs()).isEqualTo(1);
    }
}
