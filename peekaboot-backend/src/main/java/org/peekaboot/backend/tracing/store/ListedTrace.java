package org.peekaboot.backend.tracing.store;

/**
 * A trace as one bucket lists it. {@code listedAt} is the export order of the newest span the
 * store had received when the trace entered the bucket, so it compares with any span's
 * {@link SpanData#creationOrder()}: that is how the listing places a row that arrives later
 * than its trace.
 */
public record ListedTrace(TraceDataBundle bundle, long listedAt) {}
