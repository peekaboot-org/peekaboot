package net.osslabz.peekaboot.backend.domain.metrics;

/**
 * A single statistic (e.g., VALUE, COUNT, TOTAL, MAX) for a measurement.
 */
public record MetricStatistic(
    String name,
    double value
) {}
