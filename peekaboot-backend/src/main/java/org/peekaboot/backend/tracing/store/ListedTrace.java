package org.peekaboot.backend.tracing.store;

import org.peekaboot.backend.tracing.event.SpanData;

/**
 * A trace as one bucket lists it. {@code listedAt} is the export order of the newest span the
 * store had received when the trace entered the bucket, so it compares with any span's
 * {@link SpanData#creationOrder()}: that is how the listing places a row that arrives later
 * than its trace. {@code sequence} numbers every bucket entry the store makes, so it increases
 * in each bucket's insertion order and tells apart two traces that entered at the same
 * {@code listedAt}; a trace that enters a bucket again gets a new one.
 */
public record ListedTrace(TraceDataBundle bundle, long listedAt, long sequence) {}
