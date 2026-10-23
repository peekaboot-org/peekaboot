package org.peekaboot.backend.domain.metrics;

import java.util.List;

/** Every meter of the registry, grouped by metric name. */
public record MetricsInfo(List<MetricGroup> metrics) {
    public static MetricsInfo empty() {
        return new MetricsInfo(List.of());
    }
}
