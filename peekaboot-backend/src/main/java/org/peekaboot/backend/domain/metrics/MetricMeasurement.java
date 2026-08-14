package org.peekaboot.backend.domain.metrics;

import java.util.List;
import java.util.Map;

/**
 * A single measurement with its tags and statistics.
 */
public record MetricMeasurement(
    Map<String, String> tags,
    List<MetricStatistic> statistics
) {}
