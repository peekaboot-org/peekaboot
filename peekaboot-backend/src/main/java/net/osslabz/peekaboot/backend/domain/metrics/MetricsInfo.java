package net.osslabz.peekaboot.backend.domain.metrics;

import java.util.List;

/**
 * Response containing all metrics from the MeterRegistry, grouped by metric name.
 */
public record MetricsInfo(
    int metricCount,
    int measurementCount,
    List<MetricGroup> metrics
) {
    public static MetricsInfo empty() {
        return new MetricsInfo(0, 0, List.of());
    }
}
