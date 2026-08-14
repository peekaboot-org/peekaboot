package org.peekaboot.backend.domain.datasource;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PoolMetricsTest {

    @Test
    void of_shouldCalculateUsagePercent() {
        PoolMetrics metrics = PoolMetrics.of("HikariCP", 5, 3, 10, 1);

        assertThat(metrics.poolType()).isEqualTo("HikariCP");
        assertThat(metrics.activeConnections()).isEqualTo(5);
        assertThat(metrics.idleConnections()).isEqualTo(3);
        assertThat(metrics.maxConnections()).isEqualTo(10);
        assertThat(metrics.minConnections()).isEqualTo(1);
        assertThat(metrics.usagePercent()).isEqualTo(50.0);
    }

    @Test
    void of_shouldHandleZeroMax() {
        PoolMetrics metrics = PoolMetrics.of("HikariCP", 0, 0, 0, 0);
        assertThat(metrics.usagePercent()).isEqualTo(0.0);
    }

    @Test
    void of_shouldRoundToTwoDecimals() {
        PoolMetrics metrics = PoolMetrics.of("HikariCP", 1, 0, 3, 0);
        assertThat(metrics.usagePercent()).isEqualTo(33.33);
    }
}
