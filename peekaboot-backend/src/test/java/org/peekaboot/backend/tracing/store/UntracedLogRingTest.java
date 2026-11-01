package org.peekaboot.backend.tracing.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.Logs.untraced;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;

class UntracedLogRingTest {

    @Test
    void keepsTheNewestInArrivalOrder() {
        UntracedLogRing ring = new UntracedLogRing(3);
        for (int i = 1; i <= 5; i++) {
            ring.add(untraced().saying("line" + i).build());
        }

        assertThat(ring.logs()).extracting(LogCapturedEvent::message).containsExactly("line3", "line4", "line5");
    }

    @Test
    void keepsEveryLineBelowItsCapacity() {
        UntracedLogRing ring = new UntracedLogRing(3);
        ring.add(untraced().saying("line1").build());
        ring.add(untraced().saying("line2").build());

        assertThat(ring.logs()).extracting(LogCapturedEvent::message).containsExactly("line1", "line2");
    }

    @Test
    void aCapacityOfZeroKeepsNone() {
        UntracedLogRing ring = new UntracedLogRing(0);
        ring.add(untraced().build());

        assertThat(ring.logs()).isEmpty();
    }

    /** A reader holds a copy, so a line added later never shows up in what it read. */
    @Test
    void handsOutACopy() {
        UntracedLogRing ring = new UntracedLogRing(3);
        ring.add(untraced().build());
        List<LogCapturedEvent> read = ring.logs();

        ring.add(untraced().build());

        assertThat(read).hasSize(1);
    }
}
