package org.peekaboot.backend.mapper.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.tuple;
import static org.peekaboot.backend.testsupport.Logs.log;
import static org.peekaboot.backend.testsupport.Spans.asyncTask;
import static org.peekaboot.backend.testsupport.Spans.jdbcQuery;
import static org.peekaboot.backend.testsupport.Spans.span;
import static org.peekaboot.backend.tracing.store.UnstoredBundles.UNFILED;
import static org.peekaboot.backend.tracing.store.UnstoredBundles.resolvingSpanIds;

import io.micrometer.tracing.Span;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.config.PeekabootJson;
import org.peekaboot.backend.domain.api.TraceView;
import org.peekaboot.backend.domain.trace.IssueType;
import org.peekaboot.backend.domain.trace.QueryInfo;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.TraceDetail;
import org.peekaboot.backend.domain.trace.TraceIssue;
import org.peekaboot.backend.domain.trace.TraceLog;
import org.peekaboot.backend.domain.trace.TraceOutcome;
import org.peekaboot.backend.domain.trace.TraceReason;
import org.peekaboot.backend.domain.trace.TraceResource;
import org.peekaboot.backend.domain.trace.TraceSummary;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.stacktrace.StackTraceRules;
import org.peekaboot.backend.testsupport.TraceStores;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.peekaboot.backend.tracing.store.TraceDataBundle;

class TraceResourcesTest {

    private static final int SPAN_CAP = new PeekabootTracingProperties().getMaxSpansPerTrace();

    private final TraceResources resources = new TraceResources(
            new MaskingEngine(),
            SqlFormatter.NONE,
            new PeekabootTracingProperties(),
            new StackTraceRules(List.of(), List.of()));

    private InMemoryTraceStore store = TraceStores.withDefaults();

    private TraceSummary whole(String traceId) {
        return resources
                .row(store.getTrace(traceId).orElseThrow(), null, TraceView.SUMMARY)
                .withBuckets(store.bucketsOf(traceId))
                .summary();
    }

    private TraceSummary subtree(String traceId, String subtreeRootSpanId) {
        return resources
                .row(store.getTrace(traceId).orElseThrow(), subtreeRootSpanId, TraceView.SUMMARY)
                .withBuckets(store.bucketsOf(traceId))
                .summary();
    }

    private static TraceResources recordingInto(List<String> formatted) {
        return new TraceResources(
                new MaskingEngine(),
                sql -> {
                    formatted.add(sql);
                    return sql;
                },
                new PeekabootTracingProperties(),
                new StackTraceRules(List.of(), List.of()));
    }

    /**
     * Trace {@code t1}, which raises every issue type and every reason kind, with {@code abc123} planted
     * in a tag, in the exception and in the repeated statement.
     */
    private void addATraceRaisingEveryVerdict() {
        String failure = "GET /reset;token=abc123 failed";
        store.addLog(
                log("t1").inSpan("root").at("ERROR").saying("payment declined").build());
        store.addSpan(span("work").in("t1").parent("root").at(0, 600).order(1).build());
        for (int i = 0; i < 5; i++) {
            store.addSpan(jdbcQuery("query" + i, "SELECT 1 /* ;token=abc123 */")
                    .in("t1")
                    .parent("loop")
                    .at(600 + i * 60, 60)
                    .order(2 + i)
                    .build());
        }
        store.addSpan(span("loop").in("t1").parent("root").at(600, 400).order(7).build());
        store.addSpan(span("failed")
                .in("t1")
                .parent("root")
                .at(1_000, 50)
                .exception(
                        "java.lang.IllegalStateException",
                        failure,
                        "java.lang.IllegalStateException: " + failure
                                + "\n\tat org.peekaboot.app.OrderController.boom(OrderController.java:42)")
                .order(8)
                .build());
        store.addSpan(span("selfCall")
                .in("t1")
                .parent("root")
                .kind(Span.Kind.SERVER)
                .at(1_050, 50)
                .tag("status", "500")
                .tag("http.url", "/reset;token=abc123")
                .order(9)
                .build());
        store.addSpan(span("root")
                .in("t1")
                .kind(Span.Kind.SERVER)
                .at(0, 1_200)
                .tag("status", "404")
                .order(10)
                .build());
    }

    /**
     * Trace {@code t1} as a chain of {@code length} spans below {@code parentId}, each the parent of the
     * next and stored leaf first, as spans end. Each has its own name, since the store folds a child
     * into a parent of equal name and tags.
     */
    private void addAChain(int length, String parentId) {
        for (int i = length - 1; i >= 0; i--) {
            String link = "link" + i;
            store.addSpan(span(link)
                    .in("t1")
                    .named(link)
                    .parent(i == 0 ? parentId : "link" + (i - 1))
                    .at(0, 1)
                    .build());
        }
    }

    /** The generations from {@code top} down its first children, counted without recursion. */
    private static int depthOf(SpanNode top) {
        int depth = 1;
        for (SpanNode span = top;
                !span.children().isEmpty();
                span = span.children().getFirst()) {
            depth++;
        }
        return depth;
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

        TraceResource trace = resources.trace(store.getTrace("t1").orElseThrow());

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
        AtomicInteger reads = new AtomicInteger();
        TraceDataBundle movingRedirects =
                resolvingSpanIds("t1", 500, 500, spanId -> spanId + "#" + reads.getAndIncrement());
        movingRedirects.addSpan(
                span("root").in("t1").kind(Span.Kind.SERVER).at(0, 10).build(), UNFILED);
        movingRedirects.addLog(
                log("t1").inSpan("root").at("ERROR").saying("payment declined").build(), UNFILED);

        TraceResource trace = resources.trace(movingRedirects);

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
        AtomicInteger reads = new AtomicInteger();
        TraceDataBundle movingRedirects =
                resolvingSpanIds("t1", 500, 500, spanId -> reads.getAndIncrement() == 0 ? spanId : "root");
        movingRedirects.addSpan(
                span("root").in("t1").kind(Span.Kind.SERVER).at(0, 10).build(), UNFILED);
        movingRedirects.addSpan(
                asyncTask("task").in("t1").parent("root").at(2, 50).build(), UNFILED);
        movingRedirects.addLog(log("t1").inSpan("task").saying("inside").build(), UNFILED);

        TraceResource row = resources.row(movingRedirects, "task", TraceView.FULL);

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

        TraceSummary trace = resources.trace(store.getTrace("t1").orElseThrow()).summary();

        assertThat(trace.outcome()).isEqualTo(TraceOutcome.IN_PROGRESS);
        assertThat(trace.reasons()).extracting(TraceReason::kind).containsExactly(TraceReason.Kind.EXCEPTION);
    }

    @Test
    void aSummaryRowCarriesNoData() {
        store.addSpan(span("root").in("t1").kind(Span.Kind.SERVER).at(0, 10).build());

        assertThat(resources
                        .row(store.getTrace("t1").orElseThrow(), null, TraceView.SUMMARY)
                        .data())
                .isNull();
        assertThat(resources
                        .row(store.getTrace("t1").orElseThrow(), null, TraceView.FULL)
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
                .row(store.getTrace("t1").orElseThrow(), "task", TraceView.FULL)
                .data();

        assertThat(data.queries()).extracting(QueryInfo::spanId).containsExactly("inTask");
        assertThat(data.httpExchange()).isNull();
    }

    /** The statements outside an async row's subtree are not its to serve, so they are never formatted. */
    @Test
    void aSubtreeRowFormatsOnlyItsOwnStatements() {
        List<String> formatted = new ArrayList<>();
        TraceResources recording = recordingInto(formatted);
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

        recording.row(store.getTrace("t1").orElseThrow(), "task", TraceView.FULL);

        assertThat(formatted).containsExactly("SELECT 2");
    }

    /** A summary serves no statement laid out, so a page of summaries pays for no formatting. */
    @Test
    void aSummaryRowFormatsNoStatement() {
        List<String> formatted = new ArrayList<>();
        TraceResources recording = recordingInto(formatted);
        store.addSpan(
                jdbcQuery("query", "SELECT 1").in("t1").parent("root").order(1).build());
        store.addSpan(
                span("root").in("t1").kind(Span.Kind.SERVER).at(0, 10).order(2).build());
        TraceDataBundle bundle = store.getTrace("t1").orElseThrow();

        recording.row(bundle, null, TraceView.SUMMARY);

        assertThat(formatted).isEmpty();

        recording.row(bundle, null, TraceView.FULL);

        assertThat(formatted).containsExactly("SELECT 1");
    }

    /** A summary row is mapped with less span content than a full one, and judged the same. */
    @Test
    void bothViewsServeTheSameSummary() {
        addATraceRaisingEveryVerdict();
        TraceDataBundle bundle = store.getTrace("t1").orElseThrow();

        TraceSummary summary = resources.row(bundle, null, TraceView.SUMMARY).summary();

        assertThat(summary.issues()).extracting(TraceIssue::type).contains(IssueType.values());
        assertThat(summary.reasons()).extracting(TraceReason::kind).contains(TraceReason.Kind.values());
        assertThat(summary.cause()).isNotNull();
        assertThat(summary)
                .isEqualTo(resources.row(bundle, null, TraceView.FULL).summary());
    }

    /** The tree behind a summary holds its tags as stored, so the row is where they must not come out. */
    @Test
    void aSummaryRowServesNothingUnmasked() {
        addATraceRaisingEveryVerdict();

        String served = PeekabootJson.MAPPER.writeValueAsString(
                resources.row(store.getTrace("t1").orElseThrow(), null, TraceView.SUMMARY));

        assertThat(served).contains("token=******").doesNotContain("abc123");
    }

    /** The routes count issue types off a mapping of their own, and it judges as a summary's does. */
    @Test
    void theIssueTypesAreTheOnesTheSummaryLists() {
        addATraceRaisingEveryVerdict();

        Set<IssueType> types = resources.issueTypes(store.getTrace("t1").orElseThrow());

        assertThat(types)
                .isNotEmpty()
                .isEqualTo(whole("t1").issues().stream().map(TraceIssue::type).collect(Collectors.toSet()));
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

    /** A span nests two JSON levels, itself and its children, so the deepest tree the default cap stores nests a thousand. */
    @Test
    void aSpanChainAsDeepAsTheSpanCapMapsAndSerialises() {
        addAChain(SPAN_CAP - 1, "root");
        store.addSpan(span("root").in("t1").kind(Span.Kind.SERVER).at(0, 10).build());
        TraceDataBundle bundle = store.getTrace("t1").orElseThrow();

        TraceResource trace = resources.trace(bundle);
        TraceResource row = resources.row(bundle, null, TraceView.SUMMARY);

        assertThat(depthOf(trace.data().rootSpan())).isEqualTo(SPAN_CAP);
        assertThat(row.summary().counts().spans()).isEqualTo(SPAN_CAP);
        assertThatCode(() -> PeekabootJson.MAPPER.writeValueAsString(trace)).doesNotThrowAnyException();
        assertThatCode(() -> PeekabootJson.MAPPER.writeValueAsString(row)).doesNotThrowAnyException();
    }

    /** While the root is missing the chain is served under {@code orphans}, just as deep. */
    @Test
    void aDeepChainWithoutARootSerialisesAsOrphans() {
        addAChain(SPAN_CAP - 1, "root");

        TraceResource trace = resources.trace(store.getTrace("t1").orElseThrow());

        assertThat(trace.data().rootSpan()).isNull();
        assertThat(trace.data().orphans())
                .singleElement()
                .satisfies(orphan -> assertThat(depthOf(orphan)).isEqualTo(SPAN_CAP - 1));
        assertThatCode(() -> PeekabootJson.MAPPER.writeValueAsString(trace)).doesNotThrowAnyException();
    }

    /** An async row serves its task's subtree, which the cap bounds like the whole trace. */
    @Test
    void aDeepChainInsideAnAsyncRowSerialises() {
        addAChain(SPAN_CAP - 2, "task");
        store.addSpan(asyncTask("task").in("t1").parent("root").at(2, 50).build());
        store.addSpan(span("root").in("t1").kind(Span.Kind.SERVER).at(0, 10).build());

        TraceResource row = resources.row(store.getTrace("t1").orElseThrow(), "task", TraceView.FULL);

        assertThat(row.summary().subtree()).isNotNull();
        assertThat(depthOf(row.data().rootSpan())).isEqualTo(SPAN_CAP - 1);
        assertThatCode(() -> PeekabootJson.MAPPER.writeValueAsString(row)).doesNotThrowAnyException();
    }
}
