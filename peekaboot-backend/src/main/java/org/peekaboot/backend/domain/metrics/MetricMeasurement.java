package org.peekaboot.backend.domain.metrics;

import java.util.List;
import java.util.Map;

public record MetricMeasurement(Map<String, String> tags, List<MetricStatistic> statistics) {}
