package org.peekaboot.backend.tracing.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.Spans.span;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpanDataTest {

    @Test
    void theDurationRunsFromTheStartToTheEnd() {
        SpanData span = span("s1").at(40, 250).build();

        assertThat(span.duration()).isEqualTo(Duration.ofMillis(250));
        assertThat(span.durationMs()).isEqualTo(250);
    }

    @Test
    void aSpanWithoutAnEndHasNoDuration() {
        SpanData span =
                new SpanData("t1", "s1", null, "op", null, Instant.EPOCH, null, Map.of(), List.of(), null, null, 1L);

        assertThat(span.duration()).isNull();
        assertThat(span.durationMs()).isZero();
    }
}
