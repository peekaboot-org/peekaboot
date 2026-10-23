package org.peekaboot.backend.tracing.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.Logs.log;
import static org.peekaboot.backend.testsupport.Spans.jdbcDuplicate;
import static org.peekaboot.backend.testsupport.Spans.jdbcQuery;
import static org.peekaboot.backend.testsupport.Spans.span;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.trace.AsyncTaskMarker;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;

class TraceDataBundleTest {

    private static final String SQL = "SELECT * FROM person";

    @Test
    void spansReturnsSortedSnapshot() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        bundle.addSpan(span("span3").order(3).build(), 100);
        bundle.addSpan(span("span1").order(1).build(), 100);
        bundle.addSpan(span("span2").order(2).build(), 100);

        List<SpanData> spans = bundle.snapshot().spans();

        assertThat(spans).extracting(SpanData::spanId).containsExactly("span1", "span2", "span3");
    }

    /** The flag flips on the first eviction and stays: a trimmed trace never reads as complete again. */
    @Test
    void addSpanTrimsOldestBeyondLimit() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        for (int i = 1; i <= 3; i++) {
            bundle.addSpan(span("span" + i).order(i).build(), 3);
        }
        assertThat(bundle.snapshot().truncated()).isFalse();

        bundle.addSpan(span("span4").order(4).build(), 3);
        bundle.addSpan(span("span5").order(5).build(), 3);

        assertThat(bundle.snapshot().spans()).extracting(SpanData::spanId).containsExactly("span3", "span4", "span5");
        assertThat(bundle.snapshot().truncated()).isTrue();
    }

    @Test
    void collapsesADuplicateChildArrivingBeforeItsRealParent() {
        // The expected OTel BatchSpanProcessor export ordering: a duplicate span is a
        // direct child of the real span it duplicates, and a span cannot end (and so
        // export) before the ancestor containing it does - so the duplicate normally
        // arrives here first, before the real span it needs to be compared against.
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        SpanData duplicate = jdbcDuplicate("dup1", "parent1", SQL).order(1).build();
        SpanData real = jdbcQuery("parent1", SQL).order(2).build();

        bundle.addSpan(duplicate, 100);
        bundle.addSpan(real, 100);

        assertThat(bundle.snapshot().spans()).extracting(SpanData::spanId).containsExactly("parent1");
    }

    @Test
    void collapsesADuplicateWhoseRealParentIsAlreadyStored() {
        // The uncommon ordering - covered for robustness, not because it's expected in
        // production, since a faithful fold must not assume the child-before-parent
        // ordering is the only one it will ever see.
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        SpanData real = jdbcQuery("parent1", SQL).order(1).build();
        SpanData duplicate = jdbcDuplicate("dup1", "parent1", SQL).order(2).build();

        bundle.addSpan(real, 100);
        bundle.addSpan(duplicate, 100);

        assertThat(bundle.snapshot().spans()).extracting(SpanData::spanId).containsExactly("parent1");
    }

    @Test
    void reparentsAGrandchildThatArrivedBeforeItsDuplicateAncestorWasFolded() {
        // Nesting depth means the grandchild ends (and so exports) before the duplicate
        // that contains it, which in turn ends before the real span - so all three arrive
        // in the reverse of their logical parent-child order.
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        SpanData grandchild =
                jdbcDuplicate("rs1", "dup1", SQL).named("result-set").order(1).build();
        SpanData duplicate = jdbcDuplicate("dup1", "parent1", SQL).order(2).build();
        SpanData real = jdbcQuery("parent1", SQL).order(3).build();

        bundle.addSpan(grandchild, 100);
        bundle.addSpan(duplicate, 100);
        bundle.addSpan(real, 100);

        List<SpanData> spans = bundle.snapshot().spans();
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
    void resolveSpanIdFollowsTheRedirectChainToTheSurvivingSpan() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        SpanData duplicate = jdbcDuplicate("dup1", "parent1", SQL).order(1).build();
        SpanData real = jdbcQuery("parent1", SQL).order(2).build();

        bundle.addSpan(duplicate, 100);
        bundle.addSpan(real, 100);

        assertThat(bundle.resolveSpanId("dup1")).isEqualTo("parent1");
        assertThat(bundle.resolveSpanId("unrelated")).isEqualTo("unrelated");
        assertThat(bundle.resolveSpanId(null)).isNull();
    }

    @Test
    void doesNotCollapseAChildWithDifferentTagsFromItsParent() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        SpanData parent = jdbcQuery("parent1", "SELECT * FROM person").order(1).build();
        SpanData child = jdbcQuery("child1", "SELECT * FROM orders")
                .parent("parent1")
                .order(2)
                .build();

        bundle.addSpan(parent, 100);
        bundle.addSpan(child, 100);

        assertThat(bundle.snapshot().spans()).extracting(SpanData::spanId).containsExactly("parent1", "child1");
    }

    @Test
    void theCapCountsRealSpansNotDuplicateArtifacts() {
        // Five real spans, each followed by its double-instrumented duplicate arriving
        // first (as in production) - ten raw addSpan calls against a cap of five. If the
        // cap counted raw arrivals rather than folded spans, this trace would truncate.
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        long order = 1;
        for (int i = 0; i < 5; i++) {
            String realId = "real" + i;
            bundle.addSpan(
                    jdbcDuplicate("dup" + i, realId, "SELECT " + i)
                            .order(order++)
                            .build(),
                    5);
            bundle.addSpan(jdbcQuery(realId, "SELECT " + i).order(order++).build(), 5);
        }

        assertThat(bundle.snapshot().spans())
                .as("ten raw arrivals must not truncate a trace with only five real spans")
                .hasSize(5);
        assertThat(bundle.snapshot().truncated())
                .as("ten raw arrivals must not truncate a trace with only five real spans")
                .isFalse();
    }

    @Test
    void theRedirectTableIsPrunedAsRealSpansAreEvicted() {
        // The redirect table gains one entry per fold; unless it is pruned as spans are
        // evicted it grows for the trace's whole life regardless of maxSpans. Here 500
        // real+duplicate pairs are folded against a cap of 10 - unpruned, the table would
        // hold close to 500 entries by the end.
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        int cap = 10;
        int pairs = 500;
        long order = 1;
        for (int i = 0; i < pairs; i++) {
            String realId = "real" + i;
            bundle.addSpan(
                    jdbcDuplicate("dup" + i, realId, "SELECT " + i)
                            .order(order++)
                            .build(),
                    cap);
            bundle.addSpan(jdbcQuery(realId, "SELECT " + i).order(order++).build(), cap);
        }

        assertThat(bundle.snapshot().spans()).hasSize(cap);
        assertThat(bundle.snapshot().truncated()).isTrue();
        assertThat(bundle.parentRedirectCountForTesting())
                .as("the redirect table must be pruned as spans are evicted, not grow with "
                        + "every duplicate ever folded over the trace's whole life")
                .isLessThanOrEqualTo(cap * 2);
    }

    @Test
    void theRedirectTableIsPrunedAcrossChainedFoldsEvenWhenTheIntermediateSurvivorIsEvicted() {
        // Chained-redirect residue: when a duplicate (dup1) is itself
        // later folded into a further survivor, an earlier fold that had targeted dup1
        // (gc -> dup1) stays keyed on dup1 in the reverse index. dup1 was folded away and
        // never itself stored as a real span, so it is never passed to
        // pruneRedirectsPointingAt when the eventual survivor is evicted - the entry for gc
        // would leak forever instead of being pruned with it. This needs triple instrumentation
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
            bundle.addSpan(
                    jdbcQuery(gcId, "SELECT " + i)
                            .parent(dup1Id)
                            .tag("peer.service", "ds-inner")
                            .order(order++)
                            .build(),
                    cap);
            bundle.addSpan(
                    jdbcQuery(dup1Id, "SELECT " + i)
                            .parent(survivorId)
                            .tag("peer.service", "ds-mid")
                            .order(order++)
                            .build(),
                    cap);
            bundle.addSpan(jdbcQuery(survivorId, "SELECT " + i).order(order++).build(), cap);
        }

        assertThat(bundle.snapshot().spans()).hasSize(cap);
        assertThat(bundle.parentRedirectCountForTesting())
                .as("the redirect table must not leak an entry per chained fold whose "
                        + "intermediate survivor was itself later folded away and evicted")
                .isLessThanOrEqualTo(cap * 2);
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
                    bundle.addSpan(span("span" + i).order(i).build(), Integer.MAX_VALUE);
                }
            });

            // reads race the writer on purpose
            while (!writer.isDone()) {
                List<SpanData> snapshot = bundle.snapshot().spans();
                assertThat(snapshot).isSortedAccordingTo(Comparator.comparingLong(SpanData::creationOrder));
            }
            writer.get();
        } finally {
            executor.shutdownNow();
        }

        assertThat(bundle.snapshot().spans()).hasSize(totalSpans);
    }

    /** The root is the first stored span whose parent is not stored - a child that exported before its parent is not it. */
    @Test
    void rootSpanIsTheFirstStoredSpanWhoseParentIsNotStored() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        assertThat(bundle.listingRoots().root()).isNull();

        bundle.addSpan(span("child").parent("parent").order(1).build(), 10);
        assertThat(bundle.listingRoots().root().spanId()).isEqualTo("child");

        bundle.addSpan(span("parent").order(2).build(), 10);
        assertThat(bundle.listingRoots().root().spanId()).isEqualTo("parent");
    }

    /** An orphaned task is its trace's root and its only entry, until the request it ran for arrives. */
    @Test
    void listingRootsNameTheRootAndTheAsyncEntriesOfOneMoment() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        bundle.addSpan(asyncSpan("async", "root", 40, 1_000), 500);

        ListingRoots orphaned = bundle.listingRoots();
        assertThat(orphaned.root().spanId()).isEqualTo("async");
        assertThat(orphaned.asyncEntries()).extracting(SpanData::spanId).containsExactly("async");

        bundle.addSpan(plainSpan("root", null, 0, 50), 500);

        ListingRoots enclosed = bundle.listingRoots();
        assertThat(enclosed.root().spanId()).isEqualTo("root");
        assertThat(enclosed.asyncEntries()).extracting(SpanData::spanId).containsExactly("async");
    }

    @Test
    void aBundleWithoutAsyncSpansListsNoAsyncEntries() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        bundle.addSpan(plainSpan("root", null, 0, 50), 500);

        assertThat(bundle.listingRoots().asyncEntries()).isEmpty();
    }

    /**
     * The Slow bucket admits a trace by the window its spans have covered, and the listed
     * duration must be that same number: a truncated trace whose earliest spans were evicted
     * must not display a duration below the threshold it was admitted at.
     */
    @Test
    void snapshotReportsTheWindowTheSlowBucketAdmittedEvenAfterEviction() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        bundle.addSpan(span("span1").at(100, 50).order(1).build(), 2);
        bundle.addSpan(span("span2").at(200, 50).order(2).build(), 2);
        bundle.addSpan(span("span3").at(300, 50).order(3).build(), 2);

        TraceData snapshot = bundle.snapshot();

        assertThat(snapshot.spans()).extracting(SpanData::spanId).containsExactly("span2", "span3");
        assertThat(snapshot.startTime()).isEqualTo(Instant.EPOCH.plusMillis(100));
        assertThat(snapshot.duration()).isEqualTo(Duration.ofMillis(250));
        assertThat(snapshot.truncated()).isTrue();
    }

    @Test
    void snapshotReportsTheSynchronousWindowAsTheDuration() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        bundle.addSpan(plainSpan("root", null, 0, 50), 500);
        bundle.addSpan(asyncSpan("async", "root", 40, 240_000), 500);

        TraceData snapshot = bundle.snapshot();

        assertThat(snapshot.duration()).isEqualTo(Duration.ofMillis(50));
    }

    @Test
    void snapshotCarriesTheRootSpanTheBundleChose() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        bundle.addSpan(span("child").parent("parent").order(1).build(), 10);
        bundle.addSpan(span("parent").order(2).build(), 10);

        TraceData snapshot = bundle.snapshot();

        assertThat(snapshot.traceId()).isEqualTo("trace1");
        assertThat(snapshot.rootSpan().spanId()).isEqualTo("parent");
        assertThat(snapshot.truncated()).isFalse();
    }

    /**
     * The mapper hangs the tree from the snapshot's root and skips it by equality when it
     * re-parents orphans, so the root must be the same value the span list carries, parent
     * redirects included. A cycle is the only shape where no span is parentless and the
     * fallback picks a span whose parent a fold rewrote; it does not occur in production,
     * but the invariant must hold for every shape the fold can leave.
     */
    @Test
    void snapshotsRootIsAnElementOfItsSpansAfterARedirectRewroteItsParent() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        bundle.addSpan(jdbcQuery("a", "SELECT 1").parent("dup").order(1).build(), 100);
        bundle.addSpan(jdbcQuery("b", "SELECT 2").parent("a").order(2).build(), 100);
        bundle.addSpan(jdbcDuplicate("dup", "b", "SELECT 2").order(3).build(), 100);

        TraceData snapshot = bundle.snapshot();

        assertThat(snapshot.rootSpan().spanId()).isEqualTo("a");
        assertThat(snapshot.rootSpan().parentId()).isEqualTo("b");
        assertThat(snapshot.spans()).contains(snapshot.rootSpan());
    }

    @Test
    void snapshotOfAnEmptyBundleHasNoRootAndNoWindow() {
        TraceData snapshot = new TraceDataBundle("trace1").snapshot();

        assertThat(snapshot.spans()).isEmpty();
        assertThat(snapshot.rootSpan()).isNull();
        assertThat(snapshot.startTime()).isNull();
        assertThat(snapshot.duration()).isNull();
    }

    @Test
    void theSynchronousWindowMatchesTheSpanWindowWithoutAsyncSpans() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        bundle.addSpan(plainSpan("root", null, 0, 50), 500);
        bundle.addSpan(plainSpan("child", "root", 10, 20), 500);

        assertThat(bundle.synchronousWindow()).isEqualTo(bundle.spanWindow());
        assertThat(bundle.snapshot().asyncSpanIds()).isEmpty();
    }

    /**
     * The point of the feature: background work a caller never waited for must not lengthen
     * the work that triggered it.
     */
    @Test
    void theSynchronousWindowExcludesAnAsyncSubtree() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        bundle.addSpan(plainSpan("root", null, 0, 50), 500);
        bundle.addSpan(asyncSpan("async", "root", 40, 240_000), 500);

        assertThat(bundle.spanWindow()).isEqualTo(Duration.ofMillis(240_040));
        assertThat(bundle.synchronousWindow()).isEqualTo(Duration.ofMillis(50));
    }

    /**
     * Spans are stored child-before-parent, so a descendant of an async span counts as
     * synchronous until its marked parent arrives and takes it into the subtree.
     */
    @Test
    void anAsyncSubtreesDescendantsAreExcludedEvenWhenTheyArriveFirst() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        bundle.addSpan(plainSpan("deep", "async", 100, 239_000), 500);
        bundle.addSpan(asyncSpan("async", "root", 40, 240_000), 500);
        bundle.addSpan(plainSpan("root", null, 0, 50), 500);

        assertThat(bundle.synchronousWindow()).isEqualTo(Duration.ofMillis(50));
    }

    @Test
    void theSynchronousWindowFallsBackToTheSpanWindowForAPurelyAsyncBundle() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        bundle.addSpan(asyncSpan("async", "gone", 0, 3_700), 500);

        assertThat(bundle.synchronousWindow()).isEqualTo(Duration.ofMillis(3_700));
    }

    @Test
    void asyncEntriesNameTheSubtreeRootAndNotItsDescendants() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        bundle.addSpan(plainSpan("root", null, 0, 50), 500);
        bundle.addSpan(asyncSpan("async", "root", 40, 1_000), 500);
        bundle.addSpan(plainSpan("deep", "async", 50, 900), 500);

        assertThat(bundle.listingRoots().asyncEntries())
                .extracting(SpanData::spanId)
                .containsExactly("async");
        assertThat(bundle.snapshot().asyncSpanIds()).containsExactlyInAnyOrder("async", "deep");
    }

    /**
     * Async work dispatched from inside async work is genuinely nested, so the inner span is
     * part of the outer subtree rather than an entry of its own.
     */
    @Test
    void nestedAsyncWorkYieldsOnlyTheOuterEntry() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        bundle.addSpan(plainSpan("root", null, 0, 50), 500);
        bundle.addSpan(asyncSpan("outer", "root", 40, 2_000), 500);
        bundle.addSpan(asyncSpan("inner", "outer", 100, 1_000), 500);

        assertThat(bundle.listingRoots().asyncEntries())
                .extracting(SpanData::spanId)
                .containsExactly("outer");
    }

    /**
     * TraceData.duration is the number the Slow bucket admitted the trace by, and eviction
     * must not lower it - the same invariant
     * snapshotReportsTheWindowTheSlowBucketAdmittedEvenAfterEviction pins for the full window.
     */
    @Test
    void theSynchronousWindowSurvivesSpanEviction() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        bundle.addSpan(plainSpan("root", null, 0, 50), 2);
        bundle.addSpan(asyncSpan("async", "root", 40, 1_000), 2);
        bundle.addSpan(plainSpan("later", "root", 20, 10), 2);

        assertThat(bundle.snapshot().truncated()).isTrue();
        assertThat(bundle.synchronousWindow()).isEqualTo(Duration.ofMillis(50));
    }

    /**
     * A late async entry makes the bundle recompute the synchronous window, and the spans the
     * cap already evicted must still count: they are what the caller waited for, and the window
     * is the number the Slow bucket admitted the trace by.
     */
    @Test
    void aLateAsyncEntryKeepsTheSynchronousTimeOfEvictedSpans() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        bundle.addSpan(plainSpan("slow", "root", 0, 1_500), 3);
        bundle.addSpan(plainSpan("quick", "root", 10, 10), 3);
        bundle.addSpan(plainSpan("background", "async", 100, 2_900), 3);
        // evicts "slow"
        bundle.addSpan(plainSpan("later", "root", 30, 10), 3);
        // evicts "quick", and moves "background" out of the synchronous window
        bundle.addSpan(asyncSpan("async", "root", 90, 3_010), 3);

        assertThat(bundle.snapshot().truncated()).isTrue();
        assertThat(bundle.synchronousWindow()).isEqualTo(Duration.ofMillis(1_500));
    }

    /**
     * Eviction must not open a gap between the two windows where the async work adds no time of
     * its own. The late entry refolds the synchronous window after "first" was evicted, and that
     * refold has to start from the evicted span.
     */
    @Test
    void asyncWorkInsideTheSynchronousWindowKeepsBothWindowsEqualUnderEviction() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        bundle.addSpan(plainSpan("first", "root", 0, 1_500), 3);
        // shares the window's start, so its joining the subtree forces a refold
        bundle.addSpan(plainSpan("background", "async", 0, 800), 3);
        bundle.addSpan(plainSpan("quick", "root", 10, 10), 3);
        // evicts "first"
        bundle.addSpan(plainSpan("later", "root", 30, 10), 3);
        bundle.addSpan(asyncSpan("async", "root", 0, 900), 3);

        assertThat(bundle.snapshot().truncated()).isTrue();
        assertThat(bundle.synchronousWindow()).isEqualTo(Duration.ofMillis(1_500));
        assertThat(bundle.synchronousWindow()).isEqualTo(bundle.spanWindow());
    }

    /**
     * A span's stated parentId can point at a JDBC span already folded away as a duplicate by
     * the time async membership is computed, so ancestry must go through
     * {@code TraceDataBundle#resolve} rather than the raw id: skipping it loses membership for
     * any subtree hanging off a deduplicated span, and deduplicated JDBC spans are exactly what
     * an enrichment task produces.
     */
    @Test
    void asyncMembershipFollowsResolveThroughAFoldedAwayDuplicateAncestor() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        SpanData leaf = plainSpan("leaf", "dup", 100_000, 5_000);
        SpanData duplicate =
                jdbcDuplicate("dup", "real", "SELECT 1").at(150, 100).build();
        SpanData real =
                jdbcQuery("real", "SELECT 1").parent("async").at(100, 50).build();
        SpanData async = asyncSpan("async", "root", 40, 10_000);
        SpanData root = plainSpan("root", null, 0, 50);

        // child-before-parent, the real export order: leaf, then its stated parent (the
        // duplicate), then the real span the duplicate folds into, then async, then root
        bundle.addSpan(leaf, 500);
        bundle.addSpan(duplicate, 500);
        bundle.addSpan(real, 500);
        bundle.addSpan(async, 500);
        bundle.addSpan(root, 500);

        assertThat(bundle.synchronousWindow()).isEqualTo(Duration.ofMillis(50));
    }

    /**
     * The walk has to go below the entry's direct children. Storing leaf before mid, the real
     * export order, leaves a descendant two hops down that a one-level walk would miss.
     */
    @Test
    void aTwoHopDescendantStoredBeforeItsEntryLeavesTheSynchronousWindow() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        SpanData leaf = plainSpan("leaf", "mid", 20, 40_000);
        SpanData mid = plainSpan("mid", "async", 10, 50_000);
        SpanData async = asyncSpan("async", "root", 5, 100_000);
        SpanData root = plainSpan("root", null, 0, 20);

        bundle.addSpan(leaf, 500);
        bundle.addSpan(mid, 500);
        bundle.addSpan(async, 500);
        bundle.addSpan(root, 500);

        assertThat(bundle.synchronousWindow()).isEqualTo(Duration.ofMillis(20));
    }

    /**
     * A span that outlives its async parent arrives after it and joins through its parent, but
     * its own children arrived before it and have been counting as synchronous.
     */
    @Test
    void aSpanJoiningThroughItsParentTakesItsStoredDescendantsAlong() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        bundle.addSpan(plainSpan("leaf", "late", 100, 9_000), 500);
        bundle.addSpan(asyncSpan("async", "root", 40, 1_000), 500);
        bundle.addSpan(plainSpan("late", "async", 50, 9_500), 500);
        bundle.addSpan(plainSpan("root", null, 0, 50), 500);

        assertThat(bundle.snapshot().asyncSpanIds()).containsExactlyInAnyOrder("async", "late", "leaf");
        assertThat(bundle.synchronousWindow()).isEqualTo(Duration.ofMillis(50));
    }

    /**
     * The duplicate arrives after the real span it folds into, which is already async. Its
     * children were stored under the duplicate's id and now hang under the real span.
     */
    @Test
    void aDuplicateFoldedIntoAnAsyncSpanBringsItsStoredChildrenAlong() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        bundle.addSpan(plainSpan("leaf", "dup", 100_000, 5_000), 500);
        bundle.addSpan(jdbcQuery("real", "SELECT 1").parent("async").at(100, 50).build(), 500);
        bundle.addSpan(asyncSpan("async", "root", 40, 10_000), 500);
        bundle.addSpan(jdbcDuplicate("dup", "real", "SELECT 1").at(110, 30).build(), 500);
        bundle.addSpan(plainSpan("root", null, 0, 50), 500);

        assertThat(bundle.snapshot().spans()).extracting(SpanData::spanId).doesNotContain("dup");
        assertThat(bundle.synchronousWindow()).isEqualTo(Duration.ofMillis(50));
    }

    /**
     * The duplicate arrives first and counts as synchronous until the real span absorbs it. That
     * real span enters a subtree as it arrives, so the duplicate's time has to leave the window.
     */
    @Test
    void aDuplicateAbsorbedByAnAsyncSurvivorLeavesTheSynchronousWindow() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        bundle.addSpan(jdbcDuplicate("dup", "real", "SELECT 1").at(110, 5_000).build(), 500);
        bundle.addSpan(asyncSpan("async", "root", 40, 10_000), 500);
        bundle.addSpan(
                jdbcQuery("real", "SELECT 1").parent("async").at(100, 6_000).build(), 500);
        bundle.addSpan(plainSpan("root", null, 0, 50), 500);

        assertThat(bundle.snapshot().spans()).extracting(SpanData::spanId).doesNotContain("dup");
        assertThat(bundle.synchronousWindow()).isEqualTo(Duration.ofMillis(50));
    }

    /**
     * A member stays one after the entry above it is evicted: its time must not fold back into
     * the synchronous window when the next async entry arrives.
     */
    @Test
    void descendantsStoredBeforeTheirEntryIsEvictedStayOutOfTheSynchronousWindow() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        bundle.addSpan(asyncSpan("first", "root", 90, 1_000), 3);
        bundle.addSpan(plainSpan("late", "first", 100, 9_000), 3);
        bundle.addSpan(plainSpan("x", "root", 0, 50), 3);
        // evicts "first"
        bundle.addSpan(plainSpan("y", "root", 10, 10), 3);
        // evicts "late"
        bundle.addSpan(asyncSpan("second", "root", 30, 10), 3);

        assertThat(bundle.snapshot().truncated()).isTrue();
        assertThat(bundle.synchronousWindow()).isEqualTo(Duration.ofMillis(50));
    }

    @Test
    void aFoldedAwayAsyncDuplicateLeavesNoMemberBehind() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        bundle.addSpan(markedTwin("dup", "real"), 2);
        bundle.addSpan(markedTwin("real", "root"), 2);
        bundle.addSpan(plainSpan("x", "root", 0, 50), 2);
        // evicts "real", the last async span stored
        bundle.addSpan(plainSpan("y", "root", 10, 10), 2);

        assertThat(bundle.listingRoots().asyncEntries()).isEmpty();
        assertThat(bundle.snapshot().asyncSpanIds()).isEmpty();
    }

    @Test
    void addLogTrimsOldestBeyondLimit() {
        TraceDataBundle bundle = new TraceDataBundle("trace1");
        for (int i = 1; i <= 5; i++) {
            bundle.addLog(log("trace1").saying("log" + i).build(), 3);
        }

        assertThat(bundle.logs()).extracting(LogCapturedEvent::message).containsExactly("log3", "log4", "log5");
    }

    /**
     * A span carrying Peekaboot's async marker - what AsyncTaskDecorator's observation exports
     * as. Tagged with its own span id under {@code THREAD_TAG_KEY} so two async spans in the
     * same test are never tag-for-tag identical: {@link SpanDuplicateMatcher} would otherwise
     * fold a nested async span into its outer parent as a JDBC-duplicate artifact, which is not
     * the shape these tests are exercising.
     */
    private SpanData asyncSpan(String spanId, String parentId, long startOffsetMs, long durationMs) {
        return span(spanId)
                .parent(parentId)
                .named(AsyncTaskMarker.CONTEXTUAL_NAME)
                .tag(AsyncTaskMarker.TAG_KEY, AsyncTaskMarker.TAG_VALUE)
                .tag(AsyncTaskMarker.THREAD_TAG_KEY, spanId)
                .at(startOffsetMs, durationMs)
                .build();
    }

    /** An async span with no thread tag, so two of them are tag-for-tag identical and one folds into the other. */
    private SpanData markedTwin(String spanId, String parentId) {
        return span(spanId)
                .parent(parentId)
                .named(AsyncTaskMarker.CONTEXTUAL_NAME)
                .tag(AsyncTaskMarker.TAG_KEY, AsyncTaskMarker.TAG_VALUE)
                .at(0, 100)
                .build();
    }

    /** Named after its own span id, so a plain span parented under another plain span is never
     * name-and-tag identical to it and mistaken for a {@link SpanDuplicateMatcher} artifact. */
    private SpanData plainSpan(String spanId, String parentId, long startOffsetMs, long durationMs) {
        return span(spanId)
                .parent(parentId)
                .named(spanId)
                .at(startOffsetMs, durationMs)
                .build();
    }
}
