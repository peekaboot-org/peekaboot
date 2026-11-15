package org.peekaboot.backend.domain.metrics;

/**
 * A single statistic (VALUE, COUNT, TOTAL, MAX, ...) of a measurement. {@code value} is null
 * where the meter reported NaN - a gauge with nothing to measure - or an infinity, since JSON
 * has neither.
 */
public record MetricStatistic(String name, Double value) {}
