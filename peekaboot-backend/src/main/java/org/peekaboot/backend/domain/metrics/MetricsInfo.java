package org.peekaboot.backend.domain.metrics;

import java.util.List;

/** The registry's meters grouped by metric name: every one, or only those a name prefix selected. */
public record MetricsInfo(List<MetricGroup> metrics) {
    public static MetricsInfo empty() {
        return new MetricsInfo(List.of());
    }
}
