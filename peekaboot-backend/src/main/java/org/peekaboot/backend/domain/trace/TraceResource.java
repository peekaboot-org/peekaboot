package org.peekaboot.backend.domain.trace;

import org.jspecify.annotations.Nullable;

/** One trace as the API serves it. {@code data} is null on a listing row asked for with {@code view=summary}. */
public record TraceResource(TraceSummary summary, @Nullable TraceDetail data) {}
