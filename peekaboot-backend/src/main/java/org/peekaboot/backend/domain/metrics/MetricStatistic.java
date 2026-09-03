package org.peekaboot.backend.domain.metrics;

/**
 * A single statistic (VALUE, COUNT, TOTAL, MAX, ...) of a measurement. {@code value} is null
 * where the meter reported NaN - a gauge with nothing to measure - since JSON has no NaN.
 */
public record MetricStatistic(String name, Double value) {}
