package org.peekaboot.backend.tracing.digest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.tracing.digest.FailedSummaries.FRAME;
import static org.peekaboot.backend.tracing.digest.FailedSummaries.STARTED;
import static org.peekaboot.backend.tracing.digest.FailedSummaries.failed;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ErrorDigestTest {

    private static final Instant CLOCK = Instant.parse("2026-11-06T12:00:00Z");

    private final ErrorDigest digest = new ErrorDigest(Clock.fixed(CLOCK, ZoneOffset.UTC));

    @Test
    void aFailureSeenTwiceIsOneEntryCountingBoth() {
        digest.record(failed("t1").saying("first").build());
        digest.record(
                failed("t2").startedAt(STARTED.plusSeconds(1)).saying("second").build());

        assertThat(digest.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.count()).isEqualTo(2);
            assertThat(entry.firstSeen()).isEqualTo(STARTED);
            assertThat(entry.lastSeen()).isEqualTo(STARTED.plusSeconds(1));
            assertThat(entry.lastTraceId()).isEqualTo("t2");
            assertThat(entry.lastMessage()).isEqualTo("second");
        });
    }

    /** An edit that moves the throwing line keeps the failure's entry. */
    @Test
    void theSameFailureFromAMovedLineIsStillOneEntry() {
        digest.record(failed("t1").thrownAt("org.acme.Shop.buy(Shop.java:42)").build());
        digest.record(failed("t2").thrownAt("org.acme.Shop.buy(Shop.java:57)").build());

        assertThat(digest.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.count()).isEqualTo(2);
            assertThat(entry.applicationFrame()).isEqualTo("org.acme.Shop.buy(Shop.java)");
        });
    }

    /** With Logback's packaging data on, a logged frame ends in the jar it came from. */
    @Test
    void aFrameFollowedByPackagingDataStillLosesItsLineNumber() {
        digest.record(failed("t1")
                .thrownAt("org.acme.Shop.buy(Shop.java:42) ~[classes/:na]")
                .build());
        digest.record(failed("t2")
                .thrownAt("org.acme.Shop.buy(Shop.java:57) ~[classes/:na]")
                .build());

        assertThat(digest.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.count()).isEqualTo(2);
            assertThat(entry.applicationFrame()).isEqualTo("org.acme.Shop.buy(Shop.java) ~[classes/:na]");
        });
    }

    @Test
    void aFrameWithoutALineNumberIsKeptAsItIs() {
        digest.record(failed("t1").thrownAt("org.acme.Shop.buy(Native Method)").build());

        assertThat(digest.entries().getFirst().applicationFrame()).isEqualTo("org.acme.Shop.buy(Native Method)");
    }

    @Test
    void anotherRouteOrAnotherClassIsAnotherEntry() {
        digest.record(failed("t1").build());
        digest.record(failed("t2").on("/carts/{id}").build());
        digest.record(failed("t3").ofType("java.io.IOException").build());

        assertThat(digest.entries()).hasSize(3);
    }

    /** Traces complete out of order; the latest by start time stays the one the entry names. */
    @Test
    void aTraceThatStartedBeforeTheLatestCountsWithoutBecomingIt() {
        digest.record(
                failed("late").startedAt(STARTED.plusSeconds(5)).saying("late").build());
        digest.record(failed("early").saying("early").build());

        assertThat(digest.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.count()).isEqualTo(2);
            assertThat(entry.firstSeen()).isEqualTo(STARTED);
            assertThat(entry.lastTraceId()).isEqualTo("late");
            assertThat(entry.lastMessage()).isEqualTo("late");
        });
    }

    @Test
    void theEntrySeenLatestComesFirst() {
        digest.record(failed("t1").on("/a").build());
        digest.record(failed("t2").on("/b").startedAt(STARTED.plusSeconds(2)).build());
        digest.record(failed("t3").on("/c").startedAt(STARTED.plusSeconds(1)).build());

        assertThat(digest.entries()).extracting(DigestedError::route).containsExactly("/b", "/c", "/a");
    }

    @Test
    void theRunsKeepTheTenLatestEachOnceOldestFirst() {
        IntStream.range(0, 12)
                .forEach(i -> digest.record(failed("t" + i)
                        .startedAt(STARTED.plusSeconds(i))
                        .inRun(STARTED.minusSeconds(100 - i))
                        .build()));
        digest.record(failed("again")
                .startedAt(STARTED.plusSeconds(20))
                .inRun(STARTED.minusSeconds(89))
                .build());
        digest.record(failed("unready").startedAt(STARTED.plusSeconds(21)).build());

        assertThat(digest.entries().getFirst().runs())
                .containsExactlyElementsOf(IntStream.range(2, 12)
                        .mapToObj(i -> STARTED.minusSeconds(100 - i))
                        .toList());
    }

    /** The failure recorded first is seen again last, so the one seen least recently is the second. */
    @Test
    void pastFiveHundredEntriesTheOneSeenLeastRecentlyGoes() {
        IntStream.range(0, ErrorDigest.MAX_ENTRIES)
                .forEach(i -> digest.record(failed("t" + i)
                        .on("/route-" + i)
                        .startedAt(STARTED.plusSeconds(i))
                        .build()));
        digest.record(failed("again")
                .on("/route-0")
                .startedAt(STARTED.plusSeconds(ErrorDigest.MAX_ENTRIES + 1))
                .build());

        digest.record(failed("new")
                .on("/route-new")
                .startedAt(STARTED.plusSeconds(ErrorDigest.MAX_ENTRIES))
                .build());

        assertThat(digest.entries()).hasSize(ErrorDigest.MAX_ENTRIES);
        assertThat(digest.entries())
                .extracting(DigestedError::route)
                .doesNotContain("/route-1")
                .contains("/route-0", "/route-2", "/route-new");
    }

    /** A file lists the entry seen latest first, so the one seen least recently comes last. */
    @Test
    void aRestorePastFiveHundredEntriesDropsTheOneSeenLeastRecently() {
        List<DigestedError> restored = Stream.concat(
                        IntStream.rangeClosed(1, ErrorDigest.MAX_ENTRIES)
                                .mapToObj(i -> restoredEntry("/route-" + i, STARTED.plusSeconds(i))),
                        Stream.of(restoredEntry("/route-0", STARTED)))
                .toList();

        digest.restore(restored);

        assertThat(digest.entries()).hasSize(ErrorDigest.MAX_ENTRIES);
        assertThat(digest.entries())
                .extracting(DigestedError::route)
                .doesNotContain("/route-0")
                .contains("/route-1");
    }

    @Test
    void aTraceIsClaimedOnceWhileItIsRemembered() {
        assertThat(digest.claim("t1")).isTrue();
        assertThat(digest.claim("t1")).isFalse();

        IntStream.range(0, ErrorDigest.REMEMBERED_TRACES).forEach(i -> digest.claim("other" + i));

        assertThat(digest.claim("t1"))
                .as("forgotten after as many newer claims as it remembers")
                .isTrue();
    }

    @Test
    void aFailureWithoutACauseIsKeyedByItsRouteAlone() {
        digest.record(failed("t1").withoutCause().build());

        assertThat(digest.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.type()).isNull();
            assertThat(entry.applicationFrame()).isNull();
            assertThat(entry.lastMessage()).isNull();
            assertThat(entry.route()).isEqualTo("/orders/{id}");
        });
    }

    @Test
    void aSummaryWithoutAStartIsDatedByTheClock() {
        digest.record(failed("t1").startedAt(null).build());

        assertThat(digest.entries().getFirst().firstSeen()).isEqualTo(CLOCK);
    }

    /** What an earlier run wrote is what the file already holds, so a restore leaves nothing to write. */
    @Test
    void aRestoreKeepsWhatThisRunHoldsAndChangesNothingToWrite() {
        digest.record(failed("t1").build());
        long changes = digest.changes();
        DigestedError sameFailure = new DigestedError(
                "java.lang.IllegalStateException",
                "/orders/{id}",
                "org.acme.Shop.buy(Shop.java)",
                7,
                STARTED.minusSeconds(60),
                STARTED.minusSeconds(30),
                "old",
                "t0",
                List.of());
        DigestedError other = new DigestedError(
                null,
                "/carts/{id}",
                null,
                3,
                STARTED.minusSeconds(60),
                STARTED.minusSeconds(10),
                null,
                "c1",
                List.of());

        digest.restore(List.of(sameFailure, other));

        assertThat(digest.entries()).extracting(DigestedError::count).containsExactly(1, 3);
        assertThat(digest.changes()).isEqualTo(changes);
    }

    /** A restored line whose frame kept its line number is the failure this run already holds. */
    @Test
    void aRestoredFrameStillCarryingItsLineNumberIsTheFailureThisRunHolds() {
        digest.record(failed("t1").build());

        digest.restore(List.of(new DigestedError(
                "java.lang.IllegalStateException",
                "/orders/{id}",
                FRAME,
                7,
                STARTED.minusSeconds(60),
                STARTED.minusSeconds(30),
                "old",
                "t0",
                List.of())));

        assertThat(digest.entries())
                .singleElement()
                .extracting(DigestedError::count)
                .isEqualTo(1);
    }

    private static DigestedError restoredEntry(String route, Instant lastSeen) {
        return new DigestedError(null, route, null, 1, lastSeen, lastSeen, null, "restored", List.of());
    }
}
