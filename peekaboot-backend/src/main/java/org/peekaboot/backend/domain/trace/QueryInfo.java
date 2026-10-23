package org.peekaboot.backend.domain.trace;

/** One query span as the Queries tab lists it; its statement is the span's own {@link SpanNode#statement()}. */
public record QueryInfo(String spanId, String dbSystem, String dataSourceName, long durationMs, Long rowCount) {}
