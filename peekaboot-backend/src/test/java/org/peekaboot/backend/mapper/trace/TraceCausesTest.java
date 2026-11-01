package org.peekaboot.backend.mapper.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.SpanNodes.node;
import static org.peekaboot.backend.testsupport.TraceTrees.tree;

import io.micrometer.tracing.Span;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.trace.SpanExceptionInfo;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.SpanStatus;
import org.peekaboot.backend.domain.trace.TraceLog;
import org.peekaboot.backend.domain.trace.TraceSummary;
import org.peekaboot.backend.stacktrace.StackTraceFolding.Range;

class TraceCausesTest {

    private static final String TRACE = """
            java.lang.IllegalStateException: gateway down
            \tat org.springframework.web.Handler.handle(Handler.java:10)
            \tat com.acme.OrderController.boom(OrderController.java:42)
            \tat com.acme.Main.main(Main.java:5)""";

    /** A different class than {@link #TRACE}'s, so a test can tell which source a cause came from. */
    private static final String LOGGED_TRACE = """
            java.io.IOException: disk full
            \tat org.springframework.web.Handler.handle(Handler.java:10)
            \tat com.acme.ReportWriter.write(ReportWriter.java:17)
            \tat com.acme.Main.main(Main.java:5)""";

    /** Lines 2 and 3 of either trace are the application's. */
    private static final List<Range> APPLICATION_FRAMES = List.of(new Range(2, 4));

    private static SpanNode failing(String spanId, String type, String stackTrace, SpanNode... children) {
        return node(spanId)
                .status(SpanStatus.ERROR)
                .exceptionInfo(new SpanExceptionInfo(
                        type,
                        "gateway down",
                        stackTrace,
                        List.of(),
                        stackTrace == null ? List.of() : APPLICATION_FRAMES))
                .children(List.of(children))
                .build();
    }

    private static TraceLog errorLog(String spanId, String stackTrace) {
        return new TraceLog(
                "trace1",
                spanId,
                Instant.EPOCH,
                "ERROR",
                "TestLogger",
                "main",
                "logged",
                stackTrace,
                List.of(),
                stackTrace == null ? List.of() : APPLICATION_FRAMES);
    }

    private static SpanNode answering503() {
        return node("root")
                .kind(Span.Kind.SERVER)
                .tags(Map.of("http.response.status_code", "503"))
                .build();
    }

    /** The exporter's shape for a span whose status said ERROR without naming why. */
    private static SpanNode failedNamingNothing(Map<String, String> tags) {
        return node("root")
                .tags(tags)
                .status(SpanStatus.ERROR)
                .exceptionInfo(new SpanExceptionInfo(null, null, null, List.of(), List.of()))
                .build();
    }

    @Test
    void theDeepestFailingSpanIsTheCause() {
        SpanNode root = failing(
                "root",
                "java.lang.RuntimeException",
                TRACE,
                failing("handler", "java.lang.IllegalStateException", TRACE));

        assertThat(TraceCauses.of(tree(root).build(), List.of()))
                .isEqualTo(new TraceSummary.Cause(
                        "java.lang.IllegalStateException",
                        "gateway down",
                        "com.acme.OrderController.boom(OrderController.java:42)",
                        "handler"));
    }

    /** Depth counts across branches, so a failure nested deeper in a later branch wins. */
    @Test
    void aDeeperFailureInALaterBranchBeatsAShallowerOneInAnEarlierBranch() {
        SpanNode root = node("root")
                .children(List.of(
                        failing("shallow", "java.lang.RuntimeException", TRACE),
                        node("service")
                                .children(List.of(node("repository")
                                        .children(List.of(failing("deep", "java.lang.IllegalStateException", TRACE)))
                                        .build()))
                                .build()))
                .build();

        assertThat(TraceCauses.of(tree(root).build(), List.of()).spanId()).isEqualTo("deep");
    }

    @Test
    void ofTwoFailingSiblingsTheFirstWalkedIsTheCause() {
        SpanNode root = node("root")
                .children(List.of(
                        failing("first", "java.lang.IllegalStateException", TRACE),
                        failing("second", "java.io.IOException", TRACE)))
                .build();

        assertThat(TraceCauses.of(tree(root).build(), List.of()).spanId()).isEqualTo("first");
    }

    @Test
    void aSpansExceptionBeatsALoggedOne() {
        SpanNode root = failing("root", "java.lang.IllegalStateException", TRACE);

        assertThat(TraceCauses.of(tree(root).build(), List.of(errorLog("root", LOGGED_TRACE)))
                        .type())
                .isEqualTo("java.lang.IllegalStateException");
    }

    /** A logged stack trace says more than a span that only set its status, so it comes first. */
    @Test
    void aLoggedStackTraceBeatsASpanWithoutOne() {
        SpanNode root = failing("root", null, null);

        TraceSummary.Cause cause = TraceCauses.of(tree(root).build(), List.of(errorLog("root", LOGGED_TRACE)));

        assertThat(cause)
                .isEqualTo(new TraceSummary.Cause(
                        "java.io.IOException",
                        "disk full",
                        "com.acme.ReportWriter.write(ReportWriter.java:17)",
                        "root"));
    }

    @Test
    void ofTwoLoggedStackTracesTheFirstLoggedIsTheCause() {
        TraceLog first = new TraceLog(
                "trace1",
                "first",
                Instant.EPOCH,
                "ERROR",
                "TestLogger",
                "main",
                "logged",
                LOGGED_TRACE,
                List.of(),
                List.of());
        TraceLog second = new TraceLog(
                "trace1",
                "second",
                Instant.EPOCH,
                "ERROR",
                "TestLogger",
                "main",
                "logged",
                TRACE,
                List.of(),
                List.of());

        assertThat(TraceCauses.of(tree(node("root").build()).build(), List.of(first, second))
                        .spanId())
                .isEqualTo("first");
    }

    @Test
    void aFailingSpanWithoutAStackTraceBeatsAnErrorLogWithoutOne() {
        SpanNode root = node("root")
                .status(SpanStatus.ERROR)
                .exception(null, "Bad Gateway")
                .build();

        assertThat(TraceCauses.of(tree(root).build(), List.of(errorLog("root", null))))
                .isEqualTo(new TraceSummary.Cause(null, "Bad Gateway", null, "root"));
    }

    @Test
    void anErrorLogBeatsASpanThatFailedWithoutNamingWhy() {
        SpanNode root = failedNamingNothing(Map.of());

        assertThat(TraceCauses.of(tree(root).build(), List.of(errorLog("root", null))))
                .isEqualTo(new TraceSummary.Cause(null, "logged", null, "root"));
    }

    @Test
    void a5xxBeatsASpanThatFailedWithoutNamingWhy() {
        SpanNode root = failedNamingNothing(Map.of("http.response.status_code", "503"));

        assertThat(TraceCauses.of(tree(root).build(), List.of()))
                .isEqualTo(new TraceSummary.Cause(null, "Answered with status 503.", null, "root"));
    }

    @Test
    void aSpanThatFailedWithoutNamingWhyIsNoCauseOnItsOwn() {
        SpanNode withoutExceptionInfo = node("root").status(SpanStatus.ERROR).build();

        assertThat(TraceCauses.of(tree(withoutExceptionInfo).build(), List.of()))
                .isNull();
        assertThat(TraceCauses.of(tree(failedNamingNothing(Map.of())).build(), List.of()))
                .isNull();
    }

    /** A span fails by its status, as it does for the trace's reasons. */
    @Test
    void aSpanThatCarriesAnExceptionButEndedOkIsNoCause() {
        SpanNode root = node("root")
                .exceptionInfo(new SpanExceptionInfo(
                        "java.lang.IllegalStateException", "handled", TRACE, List.of(), APPLICATION_FRAMES))
                .build();

        assertThat(TraceCauses.of(tree(root).build(), List.of())).isNull();
    }

    @Test
    void aFailingSpanWithoutAStackTraceBeatsA5xx() {
        SpanNode root = node("root")
                .kind(Span.Kind.SERVER)
                .tags(Map.of("status", "502"))
                .status(SpanStatus.ERROR)
                .exception(null, "Bad Gateway")
                .build();

        assertThat(TraceCauses.of(tree(root).build(), List.of()))
                .isEqualTo(new TraceSummary.Cause(null, "Bad Gateway", null, "root"));
    }

    @Test
    void anErrorLogWithoutAStackTraceBeatsA5xx() {
        assertThat(TraceCauses.of(tree(answering503()).build(), List.of(errorLog("root", null))))
                .isEqualTo(new TraceSummary.Cause(null, "logged", null, "root"));
    }

    @Test
    void ofTwoErrorLogsWithoutAStackTraceTheFirstLoggedIsTheCause() {
        TraceLog first = new TraceLog(
                "trace1", "first", Instant.EPOCH, "ERROR", "TestLogger", "main", "logged", null, List.of(), List.of());
        TraceLog second = new TraceLog(
                "trace1", "second", Instant.EPOCH, "ERROR", "TestLogger", "main", "logged", null, List.of(), List.of());

        assertThat(TraceCauses.of(tree(node("root").build()).build(), List.of(first, second))
                        .spanId())
                .isEqualTo("first");
    }

    @Test
    void a5xxWithoutAFailureIsItsOwnCause() {
        assertThat(TraceCauses.of(tree(answering503()).build(), List.of()))
                .isEqualTo(new TraceSummary.Cause(null, "Answered with status 503.", null, "root"));
    }

    /** A CLIENT span's 5xx never fails the trace, so it names no cause either. */
    @Test
    void aClientSpans5xxIsNoCause() {
        SpanNode root = node("root")
                .children(List.of(node("call")
                        .kind(Span.Kind.CLIENT)
                        .tags(Map.of("http.response.status_code", "503"))
                        .build()))
                .build();

        assertThat(TraceCauses.of(tree(root).build(), List.of())).isNull();
    }

    @Test
    void aTraceThatDidNotFailHasNoCause() {
        assertThat(TraceCauses.of(tree(node("root").build()).build(), List.of()))
                .isNull();
    }

    @Test
    void aLoggedTraceWithoutAMessageNamesItsClassAlone() {
        TraceLog log = new TraceLog(
                "trace1",
                "root",
                Instant.EPOCH,
                "ERROR",
                "TestLogger",
                "main",
                "logged",
                "java.lang.NullPointerException\n\tat x.Y.z(Y.java:1)",
                List.of(),
                List.of());

        TraceSummary.Cause cause = TraceCauses.of(tree(node("root").build()).build(), List.of(log));

        assertThat(cause.type()).isEqualTo("java.lang.NullPointerException");
        assertThat(cause.message()).isNull();
        assertThat(cause.applicationFrame()).isNull();
    }

    /** A suppressed exception's frames carry two tabs, which the frame drops with its {@code at}. */
    @Test
    void theApplicationFrameDropsItsIndentAndItsAtPrefix() {
        String suppressed = """
                java.lang.IllegalStateException: gateway down
                \tSuppressed: java.io.IOException: close failed
                \t\tat com.acme.Pool.close(Pool.java:9)""";
        SpanNode root = node("root")
                .status(SpanStatus.ERROR)
                .exceptionInfo(new SpanExceptionInfo(
                        "java.lang.IllegalStateException",
                        "gateway down",
                        suppressed,
                        List.of(),
                        List.of(new Range(2, 3))))
                .build();

        assertThat(TraceCauses.of(tree(root).build(), List.of()).applicationFrame())
                .isEqualTo("com.acme.Pool.close(Pool.java:9)");
    }

    @Test
    void aSpanOfAnIncompleteTraceCanBeTheCause() {
        SpanNode orphan = failing("orphan", "java.lang.IllegalStateException", TRACE);

        assertThat(TraceCauses.of(
                                tree(null)
                                        .complete(false)
                                        .orphans(List.of(orphan))
                                        .build(),
                                List.of())
                        .spanId())
                .isEqualTo("orphan");
    }
}
