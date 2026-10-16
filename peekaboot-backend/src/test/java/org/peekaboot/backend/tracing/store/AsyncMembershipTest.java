package org.peekaboot.backend.tracing.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.Spans.span;

import java.time.Duration;
import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

class AsyncMembershipTest {

    private final AsyncMembership membership = new AsyncMembership();

    /** A span that set the window's edge turns out to be background work, and its time leaves. */
    @Test
    void aJoiningSpanThatSetAnEdgeLeavesTheWindowOnRefresh() {
        SpanData request = span("request").at(0, 50).build();
        SpanData background = span("background").at(10, 5_000).build();
        membership.foldSynchronous(request);
        membership.foldSynchronous(background);

        membership.join(background);
        membership.refresh(List.of(request, background));

        assertThat(membership.syncWindow().duration()).isEqualTo(Duration.ofMillis(50));
    }

    /** Eviction moves a synchronous span's time into the floor every refresh starts from. */
    @Test
    void anEvictedSynchronousSpanStaysInTheWindowAcrossARefresh() {
        SpanData evicted = span("evicted").at(0, 1_500).build();
        SpanData stored = span("stored").at(10, 10).build();
        SpanData background = span("background").at(20, 5_000).build();
        membership.foldSynchronous(evicted);
        membership.foldSynchronous(stored);
        membership.foldSynchronous(background);
        membership.evict(evicted);

        membership.join(background);
        membership.refresh(List.of(stored, background));

        assertThat(membership.syncWindow().duration()).isEqualTo(Duration.ofMillis(1_500));
    }

    @Test
    void aSpanJoinsOnlyOnce() {
        SpanData span = span("s").at(0, 10).build();

        assertThat(membership.join(span)).isTrue();
        assertThat(membership.join(span)).isFalse();
    }

    @Test
    void anEvictedOrForgottenMemberLeavesNoIdBehind() {
        membership.enter("evicted", List.of());
        membership.enter("folded", List.of());

        membership.evict(span("evicted").at(0, 10).build());
        membership.forget(span("folded").at(0, 10).build());

        assertThat(membership.isEmpty()).isTrue();
    }

    /** A duplicate lies inside its survivor, so a survivor that stays synchronous covers its time. */
    @Test
    void aDuplicateAbsorbedByASynchronousSurvivorCostsNoRefold() {
        SpanData duplicate = span("duplicate").at(10, 5_000).build();
        SpanData survivor = span("survivor").at(0, 5_050).build();
        membership.foldSynchronous(duplicate);

        membership.forget(duplicate);
        membership.foldSynchronous(survivor);
        membership.refresh(storedSpansARefoldWouldWalk(survivor));

        assertThat(membership.syncWindow().duration()).isEqualTo(Duration.ofMillis(5_050));
    }

    /** A survivor that enters a subtree takes the time of the duplicates it absorbed along. */
    @Test
    void aDuplicateAbsorbedByAnEnteringSurvivorLeavesTheWindowOnRefresh() {
        SpanData request = span("request").at(0, 50).build();
        SpanData duplicate = span("duplicate").at(10, 5_000).build();
        membership.foldSynchronous(request);
        membership.foldSynchronous(duplicate);

        membership.forget(duplicate);
        membership.enter("survivor", List.of(duplicate));
        membership.refresh(List.of(request));

        assertThat(membership.syncWindow().duration()).isEqualTo(Duration.ofMillis(50));
    }

    /** The stored spans as a refresh sees them, failing the test if it walks them: only a refold does. */
    private static Collection<SpanData> storedSpansARefoldWouldWalk(SpanData... stored) {
        return new AbstractCollection<>() {
            @Override
            public Iterator<SpanData> iterator() {
                throw new AssertionError("refresh refolded the window over the stored spans");
            }

            @Override
            public int size() {
                return stored.length;
            }
        };
    }
}
