package org.peekaboot.backend.mapper.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.Logs.log;
import static org.peekaboot.backend.testsupport.SpanNodes.node;
import static org.peekaboot.backend.testsupport.TraceTrees.tree;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.trace.IssueSeverity;
import org.peekaboot.backend.domain.trace.IssueType;
import org.peekaboot.backend.domain.trace.IssueUnit;
import org.peekaboot.backend.domain.trace.SpanIssue;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.TraceIssue;
import org.peekaboot.backend.domain.trace.TraceLog;
import org.peekaboot.backend.domain.trace.TraceSummary;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.store.TraceBucket;

class TraceSummariesTest {

    private final TraceSummaries summaries = new TraceSummaries(new PeekabootTracingProperties());

    private static TraceFacts facts(
            Set<TraceBucket> buckets, List<LogCapturedEvent> logs, int droppedSpans, int droppedLogs) {
        return new TraceFacts(false, buckets, logs, List.of(), droppedSpans, droppedLogs);
    }

    private static SpanIssue issue(String spanId) {
        return new SpanIssue(IssueType.SLOW, IssueSeverity.WARNING, spanId, 100, 100, IssueUnit.MS, "m");
    }

    /** One list of every issue, in the order the Spans tab draws the spans that carry them. */
    @Test
    void listsEveryIssueInTreeOrder() {
        SpanNode child = node("child").issues(List.of(issue("child"))).build();
        SpanNode root = node("root")
                .issues(List.of(issue("root")))
                .children(List.of(child))
                .build();

        TraceSummary summary = summaries.of(tree(root).build(), facts(Set.of(), List.of(), 0, 0));

        assertThat(summary.issues()).extracting(TraceIssue::spanId).containsExactly("root", "child");
    }

    @Test
    void countsLogsByLevelAndCarriesTheDroppedCounts() {
        List<LogCapturedEvent> logs = List.of(
                log("t").at("ERROR").saying("a").build(),
                log("t").at("ERROR").saying("b").build(),
                log("t").at("WARN").saying("c").build(),
                log("t").at("INFO").saying("d").build());

        TraceSummary summary = summaries.of(tree(node("root").build()).build(), facts(Set.of(), logs, 3, 7));

        assertThat(summary.counts().logs()).isEqualTo(new TraceSummary.LogCounts(4, 2, 1));
        assertThat(summary.counts().droppedSpans()).isEqualTo(3);
        assertThat(summary.counts().droppedLogs()).isEqualTo(7);
    }

    @Test
    void namesTheBucketsInTheirOwnOrder() {
        Set<TraceBucket> slowFirst = new LinkedHashSet<>(List.of(TraceBucket.SLOW, TraceBucket.ERRORS));

        TraceSummary summary = summaries.of(tree(node("root").build()).build(), facts(slowFirst, List.of(), 0, 0));

        assertThat(summary.buckets()).containsExactly(TraceBucket.ERRORS, TraceBucket.SLOW);
    }

    /** The cause reads the ERROR logs the facts hold, which are the ones the data serves. */
    @Test
    void namesTheCauseFromTheFactsErrorLogs() {
        TraceLog errorLog = new TraceLog("root", Instant.EPOCH, "ERROR", "logged", null, List.of(), List.of());
        TraceFacts facts = new TraceFacts(false, Set.of(), List.of(), List.of(errorLog), 0, 0);

        TraceSummary summary = summaries.of(tree(node("root").build()).build(), facts);

        assertThat(summary.cause()).isEqualTo(new TraceSummary.Cause(null, "logged", null, "root"));
    }
}
