package org.peekaboot.backend.domain.metrics;

import java.util.List;

/** One metric name; each measurement is one tag combination under it. */
public record MetricGroup(
        String name, String description, String baseUnit, String type, List<MetricMeasurement> measurements) {}
