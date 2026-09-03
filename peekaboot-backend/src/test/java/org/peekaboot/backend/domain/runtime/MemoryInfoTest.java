package org.peekaboot.backend.domain.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MemoryInfoTest {

    @Test
    void of_shouldCalculatePercentage() {
        MemoryInfo info = MemoryInfo.of(500_000_000L, 1_000_000_000L, 100_000_000L);

        assertThat(info.heapUsed()).isEqualTo(500_000_000L);
        assertThat(info.heapMax()).isEqualTo(1_000_000_000L);
        assertThat(info.heapUsedPercent()).isEqualTo(50.0);
        assertThat(info.nonHeapUsed()).isEqualTo(100_000_000L);
    }

    @Test
    void of_shouldHandleZeroMax() {
        MemoryInfo info = MemoryInfo.of(0L, 0L, 0L);
        assertThat(info.heapUsedPercent()).isEqualTo(0.0);
    }

    @Test
    void of_shouldRoundToTwoDecimals() {
        MemoryInfo info = MemoryInfo.of(333_333_333L, 1_000_000_000L, 0L);
        assertThat(info.heapUsedPercent()).isEqualTo(33.33);
    }
}
