package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DoubleRingTest {

    @Test
    void startsEmpty() {
        DoubleRing ring = new DoubleRing(4);
        assertThat(ring.size()).isZero();
        assertThat(ring.toArray()).isEmpty();
    }

    @Test
    void fillsInOrder() {
        DoubleRing ring = new DoubleRing(4);
        ring.add(1.0);
        ring.add(2.0);
        assertThat(ring.toArray()).containsExactly(1.0, 2.0);
    }

    @Test
    void overwritesOldestWhenFull() {
        DoubleRing ring = new DoubleRing(3);
        ring.add(1.0);
        ring.add(2.0);
        ring.add(3.0);
        ring.add(4.0);
        assertThat(ring.size()).isEqualTo(3);
        assertThat(ring.toArray()).containsExactly(2.0, 3.0, 4.0);
    }

    @Test
    void lastReturnsNewestNInChronologicalOrder() {
        DoubleRing ring = new DoubleRing(5);
        for (int i = 1; i <= 5; i++) ring.add(i);
        assertThat(ring.last(3)).containsExactly(3.0, 4.0, 5.0);
    }

    @Test
    void lastReturnsFewerWhenNotEnoughData() {
        DoubleRing ring = new DoubleRing(5);
        ring.add(1.0);
        assertThat(ring.last(3)).containsExactly(1.0);
    }

    @Test
    void preservesNaN() {
        DoubleRing ring = new DoubleRing(2);
        ring.add(Double.NaN);
        assertThat(ring.toArray()[0]).isNaN();
    }
}
