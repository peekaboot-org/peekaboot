package org.peekaboot.backend.domain.metrics;

import java.util.List;

/** Every meter of the registry, grouped by metric name. */
public record MetricsInfo(int metricCount, int measurementCount, List<MetricGroup> metrics) {
    public static MetricsInfo empty() {
        return new MetricsInfo(0, 0, List.of());
    }
}
