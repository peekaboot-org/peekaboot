package org.peekaboot.backend.tracing.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.Spans.span;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.tracing.event.SpanData;

class WindowTest {

    @Test
    void theEmptyWindowHasNoDuration() {
        assertThat(Window.EMPTY.duration()).isNull();
    }

    @Test
    void stretchesToCoverASpanOnEitherSide() {
        Window window = Window.EMPTY
                .including(span("a").at(100, 50).build())
                .including(span("b").at(20, 300).build());

        assertThat(window.start()).isEqualTo(Instant.EPOCH.plusMillis(20));
        assertThat(window.duration()).isEqualTo(Duration.ofMillis(300));
    }

    @Test
    void keepsItselfForASpanItAlreadyCovers() {
        Window window = Window.EMPTY.including(span("a").at(0, 100).build());

        assertThat(window.including(span("b").at(10, 20).build())).isSameAs(window);
    }

    @Test
    void isBoundedOnlyByASpanOnAnEdge() {
        SpanData first = span("first").at(0, 100).build();
        SpanData last = span("last").at(10, 200).build();
        Window window = Window.EMPTY.including(first).including(last);

        assertThat(window.isBoundedBy(first)).isTrue();
        assertThat(window.isBoundedBy(last)).isTrue();
        assertThat(window.isBoundedBy(span("inside").at(5, 50).build())).isFalse();
    }
}
