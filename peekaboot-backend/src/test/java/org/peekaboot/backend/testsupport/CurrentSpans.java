package org.peekaboot.backend.testsupport;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;

/** Stubs the span a mocked {@link Tracer} reports as current, the way the filters read it. */
public final class CurrentSpans {

    private CurrentSpans() {}

    /** A span whose context carries only {@code traceId}. */
    public static Span span(String traceId) {
        TraceContext context = mock(TraceContext.class);
        when(context.traceId()).thenReturn(traceId);
        Span span = mock(Span.class);
        when(span.context()).thenReturn(context);
        return span;
    }

    /** Makes {@link #span(String)} the tracer's current span. */
    public static void stub(Tracer tracer, String traceId) {
        Span span = span(traceId);
        when(tracer.currentSpan()).thenReturn(span);
    }

    /** Makes a span with a full context, as the Server-Timing header reads it, the tracer's current span. */
    public static void stub(Tracer tracer, String traceId, String spanId, boolean sampled) {
        Span span = span(traceId);
        TraceContext context = span.context();
        when(context.spanId()).thenReturn(spanId);
        when(context.sampled()).thenReturn(sampled);
        when(tracer.currentSpan()).thenReturn(span);
    }
}
