package org.peekaboot.backend.tracing.store;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Span;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;

class TraceDataBundleTest {

    @Test
    void spansReturnsSortedSnapshot() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        bundle.addSpan(createSpan("span3", 3), 100);
        bundle.addSpan(createSpan("span1", 1), 100);
        bundle.addSpan(createSpan("span2", 2), 100);

        List<SpanData> spans = bundle.spans();

        assertThat(spans).extracting(SpanData::spanId).containsExactly("span1", "span2", "span3");
    }

    @Test
    void addSpanTrimsOldestBeyondLimit() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        for (int i = 1; i <= 5; i++) {
            bundle.addSpan(createSpan("span" + i, i), 3);
        }

        assertThat(bundle.spans()).extracting(SpanData::spanId).containsExactly("span3", "span4", "span5");
    }

    @Test
    void addSpan_collapsesDuplicateChildArrivingBeforeItsRealParent() {
        // The expected OTel BatchSpanProcessor export ordering: a duplicate span is a
        // direct child of the real span it duplicates, and a span cannot end (and so
        // export) before the ancestor containing it does - so the duplicate normally
        // arrives here first, before the real span it needs to be compared against.
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        SpanData duplicate = jdbcSpan("dup1", "parent1", "query", "dataSource", 1);
        SpanData real = jdbcSpan("parent1", null, "query", "sample_app_db", 2);

        bundle.addSpan(duplicate, 100);
        bundle.addSpan(real, 100);

        assertThat(bundle.spans()).extracting(SpanData::spanId).containsExactly("parent1");
    }

    @Test
    void addSpan_collapsesDuplicateWhoseRealParentIsAlreadyStored() {
        // The uncommon ordering - covered for robustness, not because it's expected in
        // production, since a faithful fold must not assume the child-before-parent
        // ordering is the only one it will ever see.
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        SpanData real = jdbcSpan("parent1", null, "query", "sample_app_db", 1);
        SpanData duplicate = jdbcSpan("dup1", "parent1", "query", "dataSource", 2);

        bundle.addSpan(real, 100);
        bundle.addSpan(duplicate, 100);

        assertThat(bundle.spans()).extracting(SpanData::spanId).containsExactly("parent1");
    }

    @Test
    void addSpan_reparentsAGrandchildThatArrivedBeforeItsDuplicateAncestorWasFolded() {
        // Nesting depth means the grandchild ends (and so exports) before the duplicate
        // that contains it, which in turn ends before the real span - so all three arrive
        // in the reverse of their logical parent-child order.
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        SpanData grandchild = jdbcSpan("rs1", "dup1", "result-set", "dataSource", 1);
        SpanData duplicate = jdbcSpan("dup1", "parent1", "query", "dataSource", 2);
        SpanData real = jdbcSpan("parent1", null, "query", "sample_app_db", 3);

        bundle.addSpan(grandchild, 100);
        bundle.addSpan(duplicate, 100);
        bundle.addSpan(real, 100);

        List<SpanData> spans = bundle.spans();
        assertThat(spans).extracting(SpanData::spanId).containsExactly("rs1", "parent1");
        assertThat(spans.stream()
                        .filter(s -> "rs1".equals(s.spanId()))
                        .findFirst()
                        .orElseThrow()
                        .parentId())
                .as("the grandchild must resolve to the surviving real span, not the folded-away duplicate")
                .isEqualTo("parent1");
    }

    @Test
    void resolveSpanId_followsTheRedirectChainToTheSurvivingSpan() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        SpanData duplicate = jdbcSpan("dup1", "parent1", "query", "dataSource", 1);
        SpanData real = jdbcSpan("parent1", null, "query", "sample_app_db", 2);

        bundle.addSpan(duplicate, 100);
        bundle.addSpan(real, 100);

        assertThat(bundle.resolveSpanId("dup1")).isEqualTo("parent1");
        assertThat(bundle.resolveSpanId("unrelated")).isEqualTo("unrelated");
        assertThat(bundle.resolveSpanId(null)).isNull();
    }

    @Test
    void addSpan_doesNotCollapseAChildWithDifferentTagsFromItsParent() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        SpanData parent = jdbcSpan("parent1", null, "query", "SELECT * FROM person", "sample_app_db", 1);
        SpanData child = jdbcSpan("child1", "parent1", "query", "SELECT * FROM orders", "dataSource", 2);

        bundle.addSpan(parent, 100);
        bundle.addSpan(child, 100);

        assertThat(bundle.spans()).extracting(SpanData::spanId).containsExactly("parent1", "child1");
    }

    @Test
    void addSpan_capCountsRealSpansNotDuplicateArtifacts() {
        // Five real spans, each followed by its double-instrumented duplicate arriving
        // first (as in production) - ten raw addSpan calls against a cap of five. If the
        // cap counted raw arrivals rather than folded spans, this trace would truncate.
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        long order = 1;
        for (int i = 0; i < 5; i++) {
            String realId = "real" + i;
            bundle.addSpan(jdbcSpan("dup" + i, realId, "query", "SELECT " + i, "dataSource", order++), 5);
            bundle.addSpan(jdbcSpan(realId, null, "query", "SELECT " + i, "sample_app_db", order++), 5);
        }

        assertThat(bundle.spans())
                .as("ten raw arrivals must not truncate a trace with only five real spans")
                .hasSize(5);
        assertThat(bundle.truncated())
                .as("ten raw arrivals must not truncate a trace with only five real spans")
                .isFalse();
    }

    @Test
    void addSpan_boundsTheRedirectTableAsRealSpansAreEvicted() {
        // Regression test: the redirect table used to gain one entry per fold and never
        // shrink, so it grew for the trace's whole life regardless of maxSpans. Here 500
        // real+duplicate pairs are folded against a cap of 10 - if the table weren't pruned
        // as spans are evicted, it would hold close to 500 entries by the end.
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        int cap = 10;
        int pairs = 500;
        long order = 1;
        for (int i = 0; i < pairs; i++) {
            String realId = "real" + i;
            bundle.addSpan(jdbcSpan("dup" + i, realId, "query", "SELECT " + i, "dataSource", order++), cap);
            bundle.addSpan(jdbcSpan(realId, null, "query", "SELECT " + i, "sample_app_db", order++), cap);
        }

        assertThat(bundle.spans()).hasSize(cap);
        assertThat(bundle.truncated()).isTrue();
        assertThat(bundle.parentRedirectCountForTesting())
                .as("the redirect table must be pruned as spans are evicted, not grow with "
                        + "every duplicate ever folded over the trace's whole life")
                .isLessThanOrEqualTo(cap * 2);
    }

    @Test
    void addSpan_boundsTheRedirectTableAcrossChainedFoldsEvenWhenTheIntermediateSurvivorIsEvicted() {
        // Regression test for chained-redirect residue: when a duplicate (dup1) is itself
        // later folded into a further survivor, an earlier fold that had targeted dup1
        // (gc -> dup1) stays keyed on dup1 in the reverse index. dup1 was folded away and
        // never itself stored as a real span, so it is never passed to
        // pruneRedirectsPointingAt when the eventual survivor is evicted - the entry for gc
        // leaks forever instead of being pruned with it. This needs triple instrumentation
        // (three spans that pairwise match, nested three deep), which does not occur in
        // production - but the class Javadoc promises the redirect table "stays bounded by
        // the maxSpans cap" unconditionally, so this must hold even for that shape.
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        int cap = 10;
        int triples = 500;
        long order = 1;
        for (int i = 0; i < triples; i++) {
            String survivorId = "survivor" + i;
            String dup1Id = "dup1-" + i;
            String gcId = "gc-" + i;
            // arrival order: innermost duplicate first, then the mid duplicate, then the
            // real span - the expected child-before-parent export ordering, three deep
            bundle.addSpan(jdbcSpan(gcId, dup1Id, "query", "SELECT " + i, "ds-inner", order++), cap);
            bundle.addSpan(jdbcSpan(dup1Id, survivorId, "query", "SELECT " + i, "ds-mid", order++), cap);
            bundle.addSpan(jdbcSpan(survivorId, null, "query", "SELECT " + i, "sample_app_db", order++), cap);
        }

        assertThat(bundle.spans()).hasSize(cap);
        assertThat(bundle.parentRedirectCountForTesting())
                .as("the redirect table must not leak an entry per chained fold whose "
                        + "intermediate survivor was itself later folded away and evicted")
                .isLessThanOrEqualTo(cap * 2);
    }

    @Test
    void truncated_isFalseUntilRealSpansExceedTheCap() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        for (int i = 1; i <= 3; i++) {
            bundle.addSpan(createSpan("span" + i, i), 3);
        }

        assertThat(bundle.truncated()).isFalse();
    }

    @Test
    void truncated_becomesTrueOnceRealSpansExceedTheCapAndStaysTrue() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        for (int i = 1; i <= 5; i++) {
            bundle.addSpan(createSpan("span" + i, i), 3);
        }

        assertThat(bundle.truncated()).isTrue();
    }

    @Test
    void spansCanBeReadWhileSpansAreAdded() throws Exception {
        // The OTel batch exporter adds spans on its own thread while the
        // toolbar polls the API; reading must not throw
        // ConcurrentModificationException or return a corrupt snapshot.
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        int totalSpans = 20_000;

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> writer = executor.submit(() -> {
                for (int i = 0; i < totalSpans; i++) {
                    bundle.addSpan(createSpan("span" + i, i), Integer.MAX_VALUE);
                }
            });

            while (!writer.isDone()) {
                List<SpanData> snapshot = bundle.spans();
                assertThat(snapshot).isSortedAccordingTo(Comparator.comparingLong(SpanData::creationOrder));
            }
            writer.get();
        } finally {
            executor.shutdownNow();
        }

        assertThat(bundle.spans()).hasSize(totalSpans);
    }

    @Test
    void addLogTrimsOldestBeyondLimit() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        for (int i = 1; i <= 5; i++) {
            bundle.addLog(createLog("log" + i), 3);
        }

        assertThat(bundle.logs()).extracting(LogCapturedEvent::message).containsExactly("log3", "log4", "log5");
    }

    private LogCapturedEvent createLog(String message) {
        return new LogCapturedEvent("trace1", "span1", Instant.now(), "INFO", "TestLogger", message, "main");
    }

    /** A JDBC-shaped span carrying the same query text every time, differing only by
     * {@code peerService} - the shape {@link SpanDuplicateMatcher} treats as a duplicate. */
    private SpanData jdbcSpan(String spanId, String parentId, String name, String peerService, long creationOrder) {
        return jdbcSpan(spanId, parentId, name, "SELECT * FROM person", peerService, creationOrder);
    }

    private SpanData jdbcSpan(
            String spanId, String parentId, String name, String query, String peerService, long creationOrder) {
        Instant start = Instant.EPOCH.plusMillis(creationOrder * 100);
        return new SpanData(
                "trace1",
                spanId,
                parentId,
                name,
                Span.Kind.CLIENT,
                start,
                start.plusMillis(50),
                Duration.ofMillis(50),
                new HashMap<>(Map.of("jdbc.query[0]", query, "peer.service", peerService)),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                List.of(),
                creationOrder);
    }

    private SpanData createSpan(String spanId, long creationOrder) {
        return new SpanData(
                "trace1",
                spanId,
                null,
                "span-" + spanId,
                null,
                Instant.now(),
                Instant.now().plusMillis(10),
                Duration.ofMillis(10),
                Map.of(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                List.of(),
                creationOrder);
    }
}
