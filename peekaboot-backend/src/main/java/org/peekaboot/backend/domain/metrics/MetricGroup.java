package org.peekaboot.backend.domain.metrics;

import java.util.List;

/**
 * A group of measurements for a single metric name.
 * Each measurement represents a different tag combination.
 */
public record MetricGroup(
    String name,
    String description,
    String baseUnit,
    String type,
    List<MetricMeasurement> measurements
) {}
