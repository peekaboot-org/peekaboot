package org.peekaboot.backend.mapper.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.Spans.span;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.tracing.event.SpanData;

class SelfTimesTest {

    @Test
    void aSpanWithoutChildrenIsAllSelfTime() {
        assertThat(SelfTimes.selfMs(span("p").at(0, 100).build(), List.of())).isEqualTo(100);
    }

    @Test
    void sequentialChildrenEachTakeTheirShare() {
        SpanData parent = span("p").at(0, 100).build();

        long selfMs = SelfTimes.selfMs(
                parent,
                List.of(span("a").at(10, 20).build(), span("b").at(50, 30).build()));

        assertThat(selfMs).isEqualTo(50);
    }

    /** Parallel children cover the same wall-clock time once, so concurrent work is not subtracted twice. */
    @Test
    void overlappingChildrenCountOnce() {
        SpanData parent = span("p").at(0, 100).build();

        long selfMs = SelfTimes.selfMs(
                parent,
                List.of(span("a").at(10, 50).build(), span("b").at(30, 50).build()));

        assertThat(selfMs).isEqualTo(30);
    }

    /** A child running wholly inside another adds no time the outer one has not already covered. */
    @Test
    void aChildInsideAnotherCountsOnce() {
        SpanData parent = span("p").at(0, 100).build();

        long selfMs = SelfTimes.selfMs(
                parent,
                List.of(
                        span("outer").at(10, 80).build(),
                        span("inner").at(20, 10).build()));

        assertThat(selfMs).isEqualTo(20);
    }

    /** An async child that starts after its parent ended covers none of the parent's time. */
    @Test
    void aChildOutsideItsParentsWindowCoversNothing() {
        SpanData parent = span("p").at(0, 100).build();

        long selfMs = SelfTimes.selfMs(parent, List.of(span("later").at(150, 20).build()));

        assertThat(selfMs).isEqualTo(100);
    }

    /** An async child that outlives its parent, or a clock skew that starts one early, covers only the part inside the parent. */
    @Test
    void aChildIsClippedToItsParentsWindow() {
        SpanData parent = span("p").at(100, 100).build();

        long selfMs = SelfTimes.selfMs(
                parent,
                List.of(
                        span("early").at(50, 80).build(),
                        span("late").at(180, 500).build()));

        assertThat(selfMs).isEqualTo(50);
    }

    @Test
    void aSpanWithoutBothEndsHasNoSelfTime() {
        SpanData unended =
                new SpanData("trace1", "p", null, false, "op", null, null, null, Map.of(), List.of(), null, 1);

        assertThat(SelfTimes.selfMs(unended, List.of(span("a").at(0, 10).build())))
                .isZero();
    }

    /** Sub-millisecond gaps add up before the result is cut to milliseconds, as the duration itself is. */
    @Test
    void addsUpGapsBeforeCuttingToMilliseconds() {
        SpanData parent = span("p").at(Instant.EPOCH, Duration.ofMillis(3)).build();
        SpanData child = span("c")
                .at(Instant.EPOCH.plusNanos(600_000), Duration.ofNanos(1_800_000))
                .build();

        assertThat(SelfTimes.selfMs(parent, List.of(child))).isEqualTo(1);
    }
}
